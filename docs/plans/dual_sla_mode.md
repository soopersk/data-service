# Phase-1: Clock-Time SLA (minimal-disruptive switch from duration-based)

## Context

The system currently derives the SLA deadline from a **duration baseline** (`SlaBaselineResolver`): the incoming `StartRunRequest.slaTime` is an ISO-8601 duration (`PT2H30M`), converted to a frozen absolute deadline `slaTime = startTime + baseline·(1+thresholdPercent) + lateBand`, stored as `CalculatorRun.slaTime` (`TIMESTAMPTZ`).

For **phase-1** we want SLA expressed as a **clock time** (e.g. `"22:00"`) instead of a duration. **Phase-2** returns to duration-based. So the goal is the smallest change that delivers clock-time grading now while preserving the existing (tested) duration code for a one-line phase-2 flip.

**Key architectural insight (why this is small):** everything below `SlaBaselineResolver` is **deadline-centric** — it operates only on the absolute `Instant` in `CalculatorRun.slaTime`. On-write grading (`SlaEvaluationService`), live detection (`LiveSlaBreachDetectionJob` + `SlaMonitoringCache`), and both consumer endpoints (`/batch/runs` → `RunEntry.sla`/`slaStatus`; `/executions` → `RunPerformanceData.slaTime` + per-run `slaTime`/`slaStatus`) all read the stored deadline + `sla_band`. **None of them change.** The only code that interprets the *incoming* SLA semantics is the resolver, plus one reference-line spot in `AnalyticsService`.

**Decisions (confirmed):**
- Clock time is interpreted in **UTC**, anchored to the run's **start date**, rolled forward **+1 day** if the deadline is at/before `startTime`. UI handles timezone display.
- Coexistence via config flag `observability.sla.mode` = `CLOCK_TIME | DURATION` (default `CLOCK_TIME`); duration code stays intact.
- Clock time is the **hard ON_TIME edge**; reuse existing band-gap → `LATE` within `bandGapMs` (default 15 min), `VERY_LATE` beyond. No grading/detection changes.

## Changes

### 1. New mode enum + properties
- `domain/enums/SlaMode.java` — `enum SlaMode { CLOCK_TIME, DURATION }`.
- `config/SlaProperties.java` — new `@ConfigurationProperties(prefix = "observability.sla")` bean with one field `private SlaMode mode = SlaMode.CLOCK_TIME;`. (Does not collide with the existing `duration-based.*`, `live-tracking.*`, `live-detection.*` bindings.)
- `application.yml` (and `application-dev/prod.yml`): add `observability.sla.mode: CLOCK_TIME`.

### 2. Clock-time → deadline helper (UTC)
- Add to [TimeUtils.java](src/main/java/com/company/observability/util/TimeUtils.java) a UTC variant:
  `clockTimeDeadlineUtc(Instant startTime, LocalTime slaTimeUtc)` →
  `startDateUtc = startTime.atZone(ZoneOffset.UTC).toLocalDate();`
  `deadline = ZonedDateTime.of(startDateUtc, slaTimeUtc, ZoneOffset.UTC).toInstant();`
  `if (!deadline.isAfter(startTime)) deadline = deadline.plusDays(1);` (overnight roll).
- The existing CET `calculateSlaDeadline(...)` is left untouched (it is leftover from a prior clock-time design and uses `Europe/Amsterdam`; we are UTC-based, so we do **not** reuse it).

### 3. `SlaBaselineResolver` — branch on mode (the core change)
File: [SlaBaselineResolver.java](src/main/java/com/company/observability/service/SlaBaselineResolver.java)
- Inject `SlaProperties`. At the top of `resolve(...)`, branch:
  - `CLOCK_TIME` → new private `resolveClockTime(request)`:
    - If `slaTime` blank → `SlaResolution(null, null)` (ungraded).
    - Parse with `LocalTime.parse(slaTime.trim())` (accepts `"22:00"` and `"22:00:30"`); on failure throw `DomainValidationException("Invalid slaTime. Use clock time HH:mm (UTC), e.g. 22:00.")`.
    - `deadline = TimeUtils.clockTimeDeadlineUtc(startTime, parsed)`.
    - Return `SlaResolution(null, deadline)` — `baselineDurationMs` is null in clock mode (SLA is not a duration).
  - `DURATION` → existing logic unchanged (`resolveBaselineMs` + buffered formula).
- Keep `parseRequestSlaBaseline` / `resolveBaselineMs` for the duration path.

### 4. `RunIngestionService` — persistence + estimated/expected fallbacks
File: [RunIngestionService.java](src/main/java/com/company/observability/service/RunIngestionService.java) (`doStartRun`, `resolveEstimatedEnd`)
- `run.slaTime = resolution.deadline()` — unchanged (works for both modes).
- `expectedDurationMs` (decoupled in clock mode — it feeds `/executions` actual-vs-expected, not the SLA): persist `request.expectedDurationMs` if provided, else profile avg when `profile.hasSufficientSamples(...)`, else null. In duration mode keep current behavior (`resolution.baselineDurationMs()`).
- `resolveEstimatedEnd`: when `estimatedEndTime` absent **and** `baselineDurationMs()` is null **and** `deadline != null` (the clock-time case), default `estimatedEndTime = deadline`. Duration-mode branch unchanged.
- Live-monitoring registration (`if (liveTrackingEnabled && slaDeadline != null) registerForSlaMonitoring`) unchanged.

### 5. `AnalyticsService.resolveReferenceLines` — clock-mode reference line
File: [AnalyticsService.java:502-516](src/main/java/com/company/observability/service/AnalyticsService.java#L502-L516)
- The profile-buffered branch re-derives a *duration-based* deadline (`avgDurationMs·(1+threshold)+lateBand`) — wrong for clock mode.
- In `CLOCK_TIME` mode: use profile `avgStartMinUtc` for the start reference line (still useful) but set the deadline reference to `latestRaw.slaTime()` (the stored clock-time deadline). Keep the existing duration-buffer branch for `DURATION` mode.

### 6. No-change components (verify, don't touch)
`SlaEvaluationService`, `LiveSlaBreachDetectionJob`, `SlaMonitoringCache`, `CalculatorRunRepository.markSlaBreach`, `CalculatorStateService.toRunEntry`, all response DTOs, and DB schema (`sla_time TIMESTAMPTZ` already stores any absolute instant).

### 7. Contract / docs
- [StartRunRequest.java:44-46](src/main/java/com/company/observability/dto/request/StartRunRequest.java#L44-L46): update `slaTime` `@Schema` to "phase-1: clock time `HH:mm` in UTC (e.g. `22:00`); persisted/response `slaTime` is the derived absolute deadline."
- Update `CLAUDE.md` SLA section + `docs/user/consumer-api.md` to describe clock-time phase-1 and the `observability.sla.mode` flag.

## Test Plan
- `TimeUtilsTest`: `clockTimeDeadlineUtc` — same-day deadline; overnight roll when clock time ≤ start-of-day-after-start; DST-irrelevance (pure UTC).
- `SlaBaselineResolverTest`: **update** existing cases that assert `"22:00"` is rejected — in `CLOCK_TIME` mode it must now be accepted and produce the UTC deadline. Add: blank `slaTime` → ungraded; malformed clock string → `400`; `DURATION`-mode tests still pass (the duration cases) by setting `mode=DURATION`.
- `RunIngestionService` tests: clock-mode start stores deadline as `slaTime`; `estimatedEndTime` defaults to deadline when absent; `expectedDurationMs` decoupled (request value or profile avg).
- `SlaEvaluationServiceTest` / `LiveSlaBreachDetectionJobTest`: unchanged behavior — confirm a clock-derived deadline grades ON_TIME/LATE/VERY_LATE via the same band-gap (regression).
- Controller test: `POST /runs/start` with `"slaTime":"22:00"` → 201; legacy `"PT2H30M"` → 400 in clock mode (and the reverse in duration mode).
- Endpoint contract: `/batch/runs` `RunEntry.sla` and `/executions` `slaTime`/`slaStatus` still populate correctly from the clock-derived deadline.

## Verification (end-to-end, local)
1. `docker compose up -d`; `SPRING_PROFILES_ACTIVE=local mvn clean test` (full suite green).
2. Run app (`mvn spring-boot:run`, profile `local`). Start a run with `"slaTime":"22:00"`, `startTime` earlier same day → confirm `GET /batch/runs` returns `sla` = that day 22:00Z and `slaStatus=ON_TIME` while RUNNING.
3. Start a run with a clock time already passed (or set live-detection interval low) → confirm `LiveSlaBreachDetectionJob` flips `sla_breached=true` and `slaStatus` to `LATE`/`VERY_LATE`, and `/executions` reflects it.
4. Overnight case: `startTime` 23:00Z, `slaTime` `"06:00"` → deadline = next day 06:00Z (rolled).
5. Flip `observability.sla.mode: DURATION`, restart, send `"PT2H30M"` → confirm legacy behavior restored (phase-2 dry run).

## Assumptions
- `slaTime` clock time is **UTC**; per-calculator timezone is a UI concern.
- One global mode at a time (no per-request mixing of clock vs duration).
- `expectedDurationMs` in clock mode is an independent perf metric (request → profile avg → null), not part of the SLA.
- Clock time is a hard ON_TIME edge (no extra grace baked in); LATE/VERY_LATE reuse `bandGapMs`.
- Overnight roll is a single `+1 day`; multi-day SLAs are out of phase-1 scope.


## Implementation:

File	Change
SlaMode.java	New enum CLOCK_TIME | DURATION
SlaProperties.java	New @ConfigurationProperties(prefix="observability.sla") bean, default CLOCK_TIME
application.yml	Added observability.sla.mode: CLOCK_TIME
TimeUtils.java	Added clockTimeDeadlineUtc(startTime, slaTimeUtc) with overnight roll
SlaBaselineResolver.java	Branches on mode: CLOCK_TIME → resolveClockTime() (parses HH:mm, rolls overnight); DURATION → existing logic
RunIngestionService.java	Clock mode: expectedDurationMs from request/profile (not SLA baseline); estimatedEndTime defaults to deadline when no duration
AnalyticsService.java	resolveReferenceLines: in CLOCK_TIME uses stored slaTime; in DURATION uses buffered profile avg
StartRunRequest.java	@Schema updated to document both modes


---


# Plan: Merge calculator SLA into `calculator_metadata` (matrix + `sla_for()` resolver)

## Context

SLA durations vary by `(calculatorName, frequency, run_number)` — e.g. `capitalcalc / DAILY / RUN1 = PT2H30M`. Today they live in a **separate** Airflow Variable `calculator_sla_config` (a `{calc: {freq: {RUNn: iso}}}` matrix), read fresh by the backfill DAG with no cache. Meanwhile other Airflow services consume a different Variable `calculator_metadata` via a shared loader (24h TTL cache) that injects a `CalculatorMetadata(name, id, sla_time)` object into DAGs.

Two problems: (1) two variables to keep in sync, and (2) `CalculatorMetadata.sla_time` is a **scalar** that cannot represent the freq×run matrix.

**Decision (confirmed with user):**
- **Storage:** merge the SLA matrix into `calculator_metadata` — single source of truth, reuses the existing cached loader, extensible for future attributes.
- **Shape:** replace scalar `sla_time` with an `sla` matrix + a `sla_for(frequency, run_number)` resolver method. This is a **breaking change** for any consumer reading `.sla_time`.

## Target storage shape (`calculator_metadata` Airflow Variable)

```json
{
  "capitalcalc": {
    "id": "uuid-1",
    "sla": { "DAILY": { "RUN1": "PT2H30M", "RUN2": "PT3H" }, "MONTHLY": { "RUN1": "PT6H" } }
  },
  "riskengine": {
    "id": "uuid-2",
    "sla": { "DAILY": { "RUN1": "PT1H30M" } }
  }
}
```

Sparse-friendly: missing frequency/run keys simply resolve to `None`. The old `calculator_sla_config` variable is retired once consumers migrate.

## Resolution flow (single point)

`StartRunRequest.slaTime` (Java) is **one** string per run. The shared `build_calculator_start_payload` resolves it once via `calculator_metadata.sla_for(frequency_from_event, run_number)`. All consumers (backfill, per-calculator DAGs, other services) get SLA resolution for free — no per-DAG resolution logic.

---

## Changes in THIS repo — `airflow/example_dags/test_backfile_dag.py`

1. **Imports**: `from dataclasses import dataclass, field`; `from typing import Any, Mapping`.

2. **Move `_normalize_frequency`** (currently [line 122](airflow/example_dags/test_backfile_dag.py#L122)) **above** the dataclass — `sla_for` depends on it.

3. **Replace the `CalculatorMetadata` dataclass** ([lines 27–30](airflow/example_dags/test_backfile_dag.py#L27-L30)) to mirror the new shared shape:

   ```python
   @dataclass(frozen=True)
   class CalculatorMetadata:
       """Mirror of the shared CalculatorMetadata injected by the runtime loader.

       `sla` is the per-(frequency, run) duration matrix, e.g.
           {"DAILY": {"RUN1": "PT2H30M", "RUN2": "PT3H"}, "MONTHLY": {"RUN1": "PT6H"}}
       """
       name: str
       id: str | None = None
       sla: Mapping[str, Mapping[str, str]] = field(default_factory=dict)

       def sla_for(self, frequency: str | None, run_number: int) -> str | None:
           freq = _normalize_frequency(frequency)
           if not freq:
               return None
           return self.sla.get(freq, {}).get(f"RUN{run_number}")
   ```

4. **Delete `resolve_sla_time`** ([lines 133–148](airflow/example_dags/test_backfile_dag.py#L133-L148)) — replaced by `CalculatorMetadata.sla_for`.

5. **`validate_params`** ([line 200](airflow/example_dags/test_backfile_dag.py#L200)): read the merged variable instead of `calculator_sla_config`, and rename the returned key:
   ```python
   calculator_metadata = Variable.get("calculator_metadata", default_var=None, deserialize_json=True) or {}
   ...
   "calculator_metadata": calculator_metadata,   # replaces "sla_config"
   ```

6. **`backfill_one_date`**: replace `sla_config = params.get("sla_config", {})` ([line 242](airflow/example_dags/test_backfile_dag.py#L242)) with a case-insensitive index built once per task:
   ```python
   metadata_map = params.get("calculator_metadata", {})
   metadata_index = {k.lower(): v for k, v in metadata_map.items()}
   ```

7. **Per-run block** ([lines 295–315](airflow/example_dags/test_backfile_dag.py#L295-L315)): build the rich metadata from the index; let the builder resolve the scalar. Dry-run logging reuses `sla_for`:
   ```python
   calc_name = _ctx_data.get("class")
   freq = _ctx_data.get("frequency")
   calc_metadata = None
   if calc_name:
       entry = metadata_index.get(calc_name.lower(), {})
       calc_metadata = CalculatorMetadata(name=calc_name, id=entry.get("id"), sla=entry.get("sla", {}))
   ...
   # dry-run log: sla_time=%s -> calc_metadata.sla_for(freq, run_number) if calc_metadata else None
   ...
   posted_run_id = obs_start_run(
       start_event, tenant_id, calculator_metadata=calc_metadata, run_number=run_number
   ) or run_id
   ```

## Changes in the EXTERNAL repo (snippets to apply there — not editable here)

1. **`CalculatorMetadata`** — same shape as above (`name`, `id`, `sla` matrix, `sla_for()`); drop scalar `sla_time`.

2. **Shared loader** — populate `sla=entry.get("sla", {})` (and `id`) from each calculator's `calculator_metadata` entry into the injected object.

3. **`build_calculator_start_payload`** — resolve `slaTime` once from the metadata:
   ```python
   if calculator_metadata is not None:
       freq = event_wrapper["event"].get("context", {}).get("data", {}).get("frequency")
       sla = calculator_metadata.sla_for(freq, kwargs.get("run_number"))
       if sla:
           payload["slaTime"] = sla   # ISO-8601 duration; Java StartRunRequest.slaTime
   ```

## Migration / deployment ordering (call out — breaking change)

- The external `CalculatorMetadata` shape change + loader + `build_calculator_start_payload` must deploy **together** (the dataclass change is breaking).
- Any other service reading `.sla_time` must migrate to `sla_for(frequency, run_number)`.
- Populate the `calculator_metadata` Variable with the merged `sla` blocks **before** retiring `calculator_sla_config`.

## Verification

1. **Unit-level (this repo)**: add/extend a pytest under `airflow/tests/` for `CalculatorMetadata.sla_for` — assert `DAILY/RUN1`, `DAILY/RUN2`, `MONTHLY/RUN1` resolve correctly, and that unknown freq / missing run / unknown calculator return `None` (case-insensitive name + `D`/`M` shortcodes).
2. **Dry-run DAG**: set `calculator_metadata` Variable to the sample blob, trigger `obs_event_backfill_dag` with `dry_run=true`, confirm `[DRY-RUN] ... sla_time=PT2H30M` appears for a known RUN1 and `sla_time=None` for an unconfigured calculator.
3. **Live (small window)**: `dry_run=false`, `n_days=1` on a date with known runs; verify the `slaTime` reaching the Java `/api/v1/runs/start` matches the matrix (check Spring logs or the `sla_time` column).
4. Confirm per-calculator DAGs (external) using the injected `CalculatorMetadata.sla_for(...)` still post correct SLAs after the loader change.

