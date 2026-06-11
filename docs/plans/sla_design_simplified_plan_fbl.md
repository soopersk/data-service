# Simplify observability config, BusinessCalendar, and "Dimension" naming

## Context

Review of the SLA business-calendar redesign (commit `b538db2`) confirmed the core
`nextBusinessDay(reportingDate, runNumber)` deadline anchor is sound. But the configuration layer
around it is over-structured, and the user wants it simplified:

- `observability.dashboard` and `observability.regional-batch` YAML sections (~120 lines) have
  **zero Java consumers** — dead config, delete.
- Holidays are not needed — `nextBusinessDay()` becomes a pure weekend-skip function, so
  `BusinessCalendarProperties` **and** `BusinessCalendarService` can both go.
- `CalculatorAliasProperties` + `CalculatorDimensionProperties` are two classes on the same
  `observability` prefix, keyed by the same alias names, always consumed together — merge into one.
- "Dimension" naming replaced with concrete `regions` / `run-types` maps (user-confirmed).
- Bug fix (user-confirmed): synthetic NOT_STARTED entries discard the projected SLA and grade
  against the reporting date instead of the T+n execution day → false `VERY_LATE` for capital
  when querying Friday's reporting date on Monday.

## Target YAML

Base [application.yml](src/main/resources/application.yml) `observability` block shrinks from
~195 lines to ~55:

```yaml
observability:
  security:
    basic: { username: ..., password: ..., role: ... }   # unchanged
    admin: { username: ..., password: ... }              # unchanged

  sla:
    mode: CLOCK_TIME            # CLOCK_TIME | DURATION
    live-tracking:
      enabled: true
    duration-based:
      enabled: true
      threshold-percent: 20
      late-band-minutes: 15
      very-late-band-minutes: 30
      min-sample-size: 5
      lookback:
        daily-days: 30
        monthly-days: 395
    live-detection:
      enabled: true
      interval-ms: 120000
      initial-delay-ms: 30000
    early-warning:
      enabled: true
      interval-ms: 180000
      threshold-minutes: 10

  aggregation: ...              # unchanged

  alerts:
    channel: logging

  calculator:
    # aliases are env-specific → live in application-dev/uat/prod.yml only
    # regions / run-types are env-invariant → defined once here (Spring merges map keys across profiles)
    regions:
      capital: [WMAP, WMDE, ASIA, WMUS, AUNZ, WMCH, ZURI, LDNL, AMER, EURO]
    run-types:
      modelled-exposure: [ETD, OTC, SFT]
      gemini-hedge: [ETD, OTC, SFT]

  cache:
    eviction: { enabled: true } # unchanged
  partitions:
    management: { enabled: true } # unchanged
```

Per-env file ([application-dev.yml](src/main/resources/application-dev.yml), `-prod.yml`):

```yaml
observability:
  calculator:
    aliases:
      capital: [capitalcalcdev]
      portfolio: [portfoliocalcdev]
      group-portfolio: [grportfoliocalcdev]
      modelled-exposure: [modelledexposurecalcdev]
      gemini-hedge: [geminihedgefundcalcdev]
      consolidation: [consenrichmentcalcdev]
```

**Deleted from YAML:** `business-calendar` (holidays), `sla.sla-timezone` line (property keeps
UTC default in code), `regional-batch`, the entire `dashboard` section, and the duplicated
`calculator-dimensions` blocks in dev/prod files.

## Changes

### 1. Config classes: 3 → 1

- **New** `config/CalculatorProperties.java` — prefix `observability.calculator`, three maps:
  `aliases`, `regions`, `runTypes` (all `Map<String, List<String>>`, default empty). No nested
  config class, no enum.
- **Delete** [CalculatorAliasProperties.java](src/main/java/com/company/observability/config/CalculatorAliasProperties.java),
  [CalculatorDimensionProperties.java](src/main/java/com/company/observability/config/CalculatorDimensionProperties.java)
  (incl. `DimensionConfig`, `DimensionType`),
  [BusinessCalendarProperties.java](src/main/java/com/company/observability/config/BusinessCalendarProperties.java).
- [SlaProperties.java](src/main/java/com/company/observability/config/SlaProperties.java) keeps
  `mode` + `slaTimezone` (default `"UTC"`); only the explicit YAML line is removed.

### 2. BusinessCalendarService → static `TimeUtils.nextBusinessDay()`

- Add to existing [TimeUtils](src/main/java/com/company/observability/util/TimeUtils.java):
  `static LocalDate nextBusinessDay(LocalDate start, int n)` — advance n days skipping Sat/Sun
  (~10 lines, no holidays, no config).
- **Delete** [BusinessCalendarService.java](src/main/java/com/company/observability/service/BusinessCalendarService.java);
  callers ([SlaBaselineResolver.java](src/main/java/com/company/observability/service/SlaBaselineResolver.java),
  [CalculatorStateService.java](src/main/java/com/company/observability/service/CalculatorStateService.java))
  call the static directly — one less injected dependency each, simpler tests (pure function).

### 3. Rename "Dimension" → expected runs, driven by `regions`/`run-types`

- [CalculatorDimensionService.java](src/main/java/com/company/observability/service/CalculatorDimensionService.java)
  → **`ExpectedRunsService`**, method `padDimensions` → `padToExpected`. Looks up the alias in
  `props.getRegions()` first, then `props.getRunTypes()` — the map it's found in determines
  whether placeholders set `region` or `runType`. Aliases in neither map pass through unchanged.
- [CalculatorNameResolver.java](src/main/java/com/company/observability/service/CalculatorNameResolver.java)
  reads `calculatorProperties.getAliases()` (mechanical swap, 2 sites).
- [RunQueryController.java](src/main/java/com/company/observability/controller/RunQueryController.java#L186)
  updated call site.

### 4. Bug fix: synthetic NOT_STARTED entries (single home + correct day)

Current flow for a padded calculator with zero runs: `CalculatorStateService.buildNotStartedEntry()`
computes profile estimates + latest-run fallback + projected SLA → `padToExpected()` then drops
that entry (null region/runType matches no declared value) and rebuilds N synthetics via its own
profile lookup, losing the projected `sla` and the fallback.

- `ExpectedRunsService.pad()` reuses the synthetic entry built by `CalculatorStateService` as the
  **template** for all missing dimension values (clone with `region`/`runType` set) instead of
  recomputing. Remove its now-dead `CalculatorProfileService` + alias-props lookups
  (cleanup obligation).
- In `CalculatorStateService.buildNotStartedEntry()`: anchor `estStart`/`estEnd` to
  `TimeUtils.nextBusinessDay(reportingDate, SlaBaselineResolver.parseRunNumber(runNumber))`
  instead of `reportingDate` (runs execute T+n, not on the reporting date).
- Grade `slaStatus` against the projected `sla` deadline when present; fall back to
  `estimatedEnd` only when no SLA is projectable.

### 5. Fix stale-cache gaps in `CalculatorStateCacheService.determineTtl()`

Review findings ([CalculatorStateCacheService.java:113-143](src/main/java/com/company/observability/cache/CalculatorStateCacheService.java#L113-L143)):

- `status=null` is already handled (line 121 → 60s TTL) — and never occurs in practice; the
  comment claiming synthetics have null status is stale (they set `"NOT_STARTED"`).
- **Real bug — partial snapshot gets 4h TTL:** the entry is a snapshot of runs present at fetch
  time. For multi-region calcs (capital), if one region completes `SUCCESS` before the next
  starts, the snapshot is "all terminal clean" → 4h TTL, and with TTL-only invalidation the
  remaining 9 regions' runs (or a re-trigger after SUCCESS) are invisible for up to 4 hours.
- **`CANCELLED` counts as clean** → 4h; cancelled runs are the most likely to be re-triggered.
- Structure defaults unknown statuses to the *longest* TTL — inverted defensive default.

Fix — make the 4h bucket allowlist-only and stability-gated; everything else short:

```java
Duration determineTtl(CalculatorEntry entry, LocalDate reportingDate) {
    // empty / any null status / any NOT_STARTED-only  → TTL_NOT_STARTED (unchanged)
    // any RUNNING                                     → TTL_ANY_RUNNING (unchanged)
    boolean allSuccessClean = runs.stream().allMatch(r ->
            "SUCCESS".equals(r.status())
            && !"LATE".equals(r.slaStatus()) && !"VERY_LATE".equals(r.slaStatus()));
    if (!allSuccessClean) {
        return TTL_TERMINAL_WITH_FAILURES;   // FAILED/TIMEOUT/CANCELLED/breached/unknown → 5 min
    }
    // SUCCESS snapshot may still be partial (later regions / re-triggers).
    // Only old reporting dates are truly stable.
    return reportingDate.isBefore(LocalDate.now().minusDays(3))
            ? TTL_TERMINAL_CLEAN              // 4 h — historical, no new runs plausible
            : TTL_TERMINAL_WITH_FAILURES;     // 5 min — current cycle, runs may still arrive
}
```

(The 3-day stability horizon mirrors the DAILY query window in CLAUDE.md; `reportingDate` is
already available at the `putEntries` call site.) Also delete the stale null-status comment.
Rejected alternative: event-driven eviction on `RunStartedEvent` — precise (≤30s) but adds
listener + key-index plumbing for a gap the age gate closes at 5 minutes; not worth it now.

### 6. Tests

Update `SlaBaselineResolverTest`, `CalculatorStateServiceTest`, `CalculatorDimensionServiceTest`
(rename → `ExpectedRunsServiceTest`) to the new config shape, static `nextBusinessDay`, and new
grading expectations (Friday reporting date + run_number=1 → estimates and grading anchored to
Monday). Add a `TimeUtils.nextBusinessDay` unit test (Fri+1→Mon, Fri+2→Tue, n=0→same day).
Add `determineTtl` cases: partial SUCCESS on current date → 5 min; SUCCESS on old date → 4 h;
CANCELLED → 5 min.

## Files Touched

| File | Change |
|---|---|
| `config/CalculatorProperties.java` | **new** (~25 lines: aliases, regions, runTypes) |
| `config/CalculatorAliasProperties.java` | delete |
| `config/CalculatorDimensionProperties.java` | delete |
| `config/BusinessCalendarProperties.java` | delete |
| `service/BusinessCalendarService.java` | delete |
| `util/TimeUtils.java` | + static `nextBusinessDay` |
| `service/CalculatorDimensionService.java` | → `ExpectedRunsService`; template reuse; drop profile/alias deps |
| `service/CalculatorStateService.java` | static call; business-day anchoring; grade vs projected SLA |
| `service/SlaBaselineResolver.java` | static call, drop injected calendar |
| `service/CalculatorNameResolver.java` | read `CalculatorProperties.aliases` |
| `controller/RunQueryController.java` | renamed service/method |
| `cache/CalculatorStateCacheService.java` | allowlist + age-gated TTL; CANCELLED → 5 min; pass `reportingDate` |
| `application.yml` | delete dashboard/regional-batch/business-calendar/sla-timezone; add `calculator.regions/run-types` |
| `application-dev.yml`, `-prod.yml` | `calculator-aliases`/`calculator-dimensions` → `calculator.aliases` only |
| 3 test classes + 1 new | updated/renamed |

Net: −3 config classes, −1 service, ~−170 YAML lines, one wrong-day grading bug fixed.

## Verification

1. `SPRING_PROFILES_ACTIVE=local mvn clean test` (Docker compose up first) — full suite green.
2. Boot on `local`/`dev` profile — confirms YAML binds (a typo'd key would fail fast at startup
   only for unknown enum-typed values; verify padding by request below).
3. `GET /batch/runs?keys=capital&reporting_date=<Friday>&run_number=1` with zero runs →
   10 region placeholders, each carrying projected `sla` on the following Monday and
   `slaStatus=ON_TIME` before that deadline (fails today — the bug).
4. `GET /batch/runs?keys=modelled-exposure...` → 3 run-type placeholders (run-types map path).
5. Grep for leftovers: `Dimension`, `business-calendar`, `calculator-aliases`,
   `calculator-dimensions` should have no remaining production references.


## What was implemented
Config: 3 classes → 1

New CalculatorProperties.java — observability.calculator.* with aliases, regions, runTypes maps.
Deleted CalculatorAliasProperties, CalculatorDimensionProperties (incl. DimensionConfig/DimensionType), BusinessCalendarProperties, and the BusinessCalendarService.
Business-day logic → static util

TimeUtils.nextBusinessDay(start, n) — weekend-skip only, no holidays/config. SlaBaselineResolver and CalculatorStateService now call it statically (one fewer injected dependency each).
"Dimension" → expected runs

CalculatorDimensionService → ExpectedRunsService.java; padDimensions(...4 args) → padToExpected(map). Region vs run-type is decided by which map (regions/runTypes) the alias appears in. Dropped the CalculatorProfileService, alias-props, and DurationBasedSlaProperties dependencies.
Bug fix (§4) — synthetic NOT_STARTED entries:

padToExpected reuses the upstream synthetic as a clone template (preserving projected sla/estimates), with a bare placeholder fallback.
buildNotStartedEntry anchors estStart/estEnd to nextBusinessDay(reportingDate, runNumber) (Fri reporting date queried Monday no longer false-VERY_LATE), and grades against the projected SLA when present.
TTL fix (§5) — determineTtl(entry, reportingDate): 4h bucket is now allowlist-only (all-SUCCESS, SLA-clean) and age-gated (reporting date >3 days old); CANCELLED/partial/current-cycle snapshots → 5 min.

YAML — removed business-calendar, sla-timezone line, regional-batch, dashboard (~127 lines from base); added calculator.regions/run-types; dev/prod calculator-dimensions+calculator-aliases → calculator.aliases (all 10 aliases preserved).

RunEntry gained @Builder(toBuilder = true) for template cloning.

### File changes (git status):

```
  new file:   docs/plans/sla_design_simplified_plan_fbl.md
  modified:   src/main/java/com/company/observability/cache/CalculatorStateCacheService.java
  deleted:    src/main/java/com/company/observability/config/BusinessCalendarProperties.java
  deleted:    src/main/java/com/company/observability/config/CalculatorAliasProperties.java
  deleted:    src/main/java/com/company/observability/config/CalculatorDimensionProperties.java
  new file:   src/main/java/com/company/observability/config/CalculatorProperties.java
  modified:   src/main/java/com/company/observability/controller/RunQueryController.java
  modified:   src/main/java/com/company/observability/dto/response/CalculatorBatchRunsResponse.java
  deleted:    src/main/java/com/company/observability/service/BusinessCalendarService.java
  deleted:    src/main/java/com/company/observability/service/CalculatorDimensionService.java
  modified:   src/main/java/com/company/observability/service/CalculatorNameResolver.java
  modified:   src/main/java/com/company/observability/service/CalculatorStateService.java
  new file:   src/main/java/com/company/observability/service/ExpectedRunsService.java
  modified:   src/main/java/com/company/observability/service/SlaBaselineResolver.java
  modified:   src/main/java/com/company/observability/util/TimeUtils.java
  modified:   src/main/resources/application-dev.yml
  modified:   src/main/resources/application.yml
  modified:   src/test/java/com/company/observability/cache/AnalyticsCacheServiceTest.java
  modified:   src/test/java/com/company/observability/cache/CalculatorStateCacheServiceTest.java
  modified:   src/test/java/com/company/observability/controller/RunQueryControllerTest.java
  modified:   src/test/java/com/company/observability/service/AnalyticsServiceTest.java
  deleted:    src/test/java/com/company/observability/service/CalculatorDimensionServiceTest.java
  modified:   src/test/java/com/company/observability/service/CalculatorNameResolverTest.java
  modified:   src/test/java/com/company/observability/service/CalculatorStateServiceTest.java
  new file:   src/test/java/com/company/observability/service/ExpectedRunsServiceTest.java
  modified:   src/test/java/com/company/observability/service/SlaBaselineResolverTest.java
  modified:   src/test/java/com/company/observability/util/TimeUtilsTest.java
```

```

File	Change
V9__calculator_sli_daily_dimension.sql	New migration: adds dimension_value VARCHAR(20) NOT NULL DEFAULT 'ALL' to calculator_sli_daily, new 5-col PK, new index
DailyAggregateRepository.java	Recompute SQL now groups by COALESCE(region, run_type, 'ALL'); two new reads: findProfileByRunNumberAndDimension + findAllProfilesByRunNumberAndDimension
CalculatorProfile.java	Added nullable dimensionValue field to record + @JsonCreator + fromSums (old JSON without field deserializes to null)
CalculatorProfileService.java	New 4-arg getProfile(name, freq, runNumber, dim) overload; key() and warm() updated for dimension tier
DailyAggregationJob.java	Third warming tier via findAllProfilesByRunNumberAndDimension
ExpectedRunsService.java	New 4-arg signature; per-dimension estimates from dimension profile; calculator-level deadline from sibling runs → template → null
CalculatorStateService.java	buildNotStartedEntry restructured: estimates and deadline resolved independently; profile path now also carries projected SLA
RunQueryController.java	Updated padToExpected call site to 4-arg signature
5 test classes + 3 test call-site fixes	All unit tests green (373 passing, 2 Docker-only integration tests skipped per plan)

```

# Unified Self-Describing SLA Spec (`T+N@HH:mm` / bare `HH:mm` / ISO duration)

## Context

The business defines SLAs per calculator + frequency + run_number as a **day offset (T+N business days) plus a UTC cutoff time** (e.g. Run1 → T+1 09:30, Run2 → T+2 21:30). Special calculators (Modelled Exposure, Gemini) key the offset on **run_type** instead (SFT/OTC → T+1, ETD → T+2 business days), identical across run numbers. Weekends are skipped: Fri T+1 = Mon, Fri T+2 = Tue.

**Current flaw:** `SlaBaselineResolver` (CLOCK_TIME mode) hard-wires `offset = run_number` (`parseRunNumber`, default T+2). The request can only carry `"HH:mm"` — the offset is unexpressible. Wrong whenever offset ≠ run_number (ME/Gemini, and any calculator with arbitrary per-run offsets).

**Decisions made with the user:**
- Airflow remains the SLA store (its calculator catalogue) and sends a **resolved SLA per run** in `StartRunRequest.slaTime`, already in UTC.
- Keep the freeze-at-ingestion model: one absolute deadline derived at `/start`, stored in `calculator_runs.sla_time`; grading, live detection, queries compare against it (unchanged).
- `slaTime` becomes **self-describing**; no global mode switch. Switching a calculator to duration-based SLA later = Airflow changes the catalogue value. Zero service change.
- **No persistence of the raw spec** — log it at ingestion only. No schema change anywhere.
- `expectedDurationMs` is always populated (request → profile average), never gated by config.

## The contract: `slaTime` formats

Clock anchoring is **frequency-dependent**: DAILY anchors to `reporting_date`; MONTHLY anchors to the **date component of `startTime`** with overnight roll.

| `slaTime` value | DAILY | MONTHLY |
|---|---|---|
| `"T+2@21:30"` | `nextBusinessDay(reportingDate, N)` at `21:30` in `slaTimezone` (default UTC). Deadline is the exact cutoff — **no lateBand added** (bands are grading-only, matching current CLOCK_TIME behavior) | **`DomainValidationException`** — MONTHLY clock SLA must be bare `HH:mm` |
| `"02:00"` (bare clock) | **offset fallback**: no `T+N` in spec → `N = parseRunNumber(runNumber)` (run 1 → 1, run 2 → 2, null/invalid → 2). Deadline = `nextBusinessDay(reportingDate, N)` at `02:00`. Identical to current `resolveClockTime()`. Supported form, not deprecated | **canonical**: `TimeUtils.clockTimeDeadlineUtc(startTime, 02:00)` — startTime's UTC date at 02:00, rolled +1 day if at/before startTime. Existing util, reuse as-is |
| `"PT2H30M"` (ISO-8601 duration) | deadline = `startTime + duration×(1+thresholdPercent/100) + lateBandMs` — existing DURATION path unchanged; `baselineDurationMs = duration.toMillis()` | same |
| blank/null | duration fallback chain, **always active** (no `enabled` gate): `request.expectedDurationMs` → profile avg (needs `hasSufficientSamples(minSampleSize)`) → ungraded (`SlaResolution(null, null)` + existing `obs.sla.baseline.ungraded` counter) | same |
| anything else (e.g. `"T+0@09:30"`, `"9:30"`, garbage) | `DomainValidationException` listing the three accepted forms (`T+N@HH:mm` with N ≥ 1, `HH:mm`, ISO-8601 duration) | same |

Recommended Airflow catalogue shape (their repo — document in PR description only):
```json
"sla": {
  "DAILY":   { "RUN1": "T+1@09:30", "RUN2": "T+2@21:30" },
  "MONTHLY": { "RUN1": "02:00", "RUN2": "18:00" }
}
// ME/Gemini — keyed by run_type, same for all run numbers:
"sla": { "DAILY": { "SFT": "T+1@19:30", "OTC": "T+1@19:30", "ETD": "T+2@19:30" } }
```
The DAG picks the entry for the run it triggers (it knows run_number/run_type) and sends one string.

## Changes by file

### 1. `service/SlaBaselineResolver.java` — spec parsing + frequency-aware dispatch

- Add a **private nested record** (same KISS pattern as the existing nested `SlaResolution`):
  ```java
  private record ParsedSpec(Integer offsetDays, LocalTime cutoff, Duration duration) {}
  // exactly one of (offsetDays+cutoff) | (cutoff only) | (duration) is set
  ```
  and `private static ParsedSpec parseSpec(String raw)`:
  1. regex `^T\+(\d+)@(\d{2}:\d{2})$` → offset+cutoff; reject N < 1 with `DomainValidationException`.
  2. else `Duration.parse` succeeds → duration; reject non-positive (keep existing message "slaTime must be a positive ISO-8601 duration").
  3. else `LocalTime.parse` succeeds → bare cutoff.
  4. else `DomainValidationException` naming all three accepted forms.
- Rewrite `resolve(request, frequency, profile)` ([current dispatch at lines 54–83](src/main/java/com/company/observability/service/SlaBaselineResolver.java#L54-L83)):
  - `startTime == null` → `SlaResolution(null, null)` (unchanged guard).
  - blank `slaTime` → duration fallback chain (existing `resolveBaselineMs` steps 2–3: `expectedDurationMs` → profile avg), **delete the `props.isEnabled()` check at line 65**. Found baseline → existing buffered-deadline math; none → ungraded counter + `SlaResolution(null, null)`.
  - `ParsedSpec.duration` → existing duration math (lines 77–78): `deadline = startTime + round(ms×(1+thresholdPercent/100)) + lateBandMs`; `baselineDurationMs = ms`.
  - `ParsedSpec.offsetDays+cutoff`:
    - DAILY → `executionDate = TimeUtils.nextBusinessDay(request.getReportingDate(), offsetDays)`; `deadline = ZonedDateTime.of(executionDate, cutoff, ZoneId.of(slaProperties.getSlaTimezone())).toInstant()`; `baselineDurationMs = null`. Requires `reportingDate != null` (already `@NotNull` on the DTO; keep the existing null-guard returning ungraded).
    - MONTHLY → `DomainValidationException("MONTHLY clock SLA must be a bare clock time HH:mm")`.
  - `ParsedSpec.cutoff` only (bare clock):
    - DAILY → current `resolveClockTime()` body: `nextBusinessDay(reportingDate, parseRunNumber(request.getRunNumber()))` at cutoff in `slaTimezone`.
    - MONTHLY → `TimeUtils.clockTimeDeadlineUtc(request.getStartTime(), cutoff)`.
- **Delete** the `slaProperties.getMode()` branch (line 60) and the `SlaMode` import. Keep `parseRunNumber` (still used by bare-clock DAILY, projection fallback, `ExpectedRunsService`, `CalculatorStateService`).
- Keep the `event=sla.baseline.resolve` debug logs, adding the parsed form (tplus/clock/duration/fallback) as a field.

### 2. Kill the global mode + the `enabled` gate

- Delete `domain/enums/SlaMode.java`.
- [SlaProperties.java](src/main/java/com/company/observability/config/SlaProperties.java): remove the `mode` field + import; fix the `slaTimezone` javadoc (currently suggests `"Europe/London"` for CET — wrong; London is GMT/BST. All times are UTC per requirement; default stays `UTC`).
- `DurationBasedSlaProperties`: remove `enabled` (orphaned — its only consumer was resolver line 65); keep `thresholdPercent`.
- [application.yml](src/main/resources/application.yml): remove `observability.sla.mode` (line 96) and `observability.sla.duration-based.enabled` (line 114); keep `threshold-percent`; update the surrounding comments that talk about "both SLA modes".
- Other `SlaMode` consumers get **mode-free equivalents** (behavior preserved for both spec kinds, decided per-run by data instead of global config):
  - [RunIngestionService.java:292-296](src/main/java/com/company/observability/service/RunIngestionService.java#L292-L296) (`resolveEstimatedEnd`): the mode check is redundant — execution only reaches there when `baselineDurationMs == null`, which means the deadline is clock-derived. Replace condition with `slaResolution != null && slaResolution.deadline() != null`.
  - [RunIngestionService.java:312-324](src/main/java/com/company/observability/service/RunIngestionService.java#L312-L324) (`resolveExpectedDuration`): collapse both branches into the user-mandated chain: `request.expectedDurationMs` (>0) → `profile.avgDurationMs()` (with `hasSufficientSamples`) → `slaResolution.baselineDurationMs()` → null. (Semantic note for the reviewer: for duration-spec runs the profile average now wins over the spec duration — expected duration is the historical expectation, the spec is the limit.)
  - [AnalyticsService.java:514-534](src/main/java/com/company/observability/service/AnalyticsService.java#L514-L534) (`resolveReferenceLines`): replace the mode branch with data-driven: use `latestRaw.slaTime()` as the SLA reference line whenever non-null (it is the frozen deadline in every mode); fall back to `estStart + buffered profile avg` only when null.
- Update tests that set the mode/flag: `SlaBaselineResolverTest` (line 218 `setMode(DURATION)`, line 272 `setEnabled(false)`), `RunIngestionServiceTest:208`, `AnalyticsServiceTest:379` — duration behavior is now triggered by passing a duration spec, the enabled=false case becomes "blank slaTime with no fallback data → ungraded".

### 3. `RunIngestionService.startRun()` — log the raw spec

No persistence (user decision). Add the raw request `slaTime` to the existing success log ([line 133-134](src/main/java/com/company/observability/service/RunIngestionService.java#L133-L134)): `event=run.start.persist outcome=success slaSpec={} slaDeadline={} ...`.

### 4. NOT_STARTED projection — derive the offset, scope by run_number

`/api/v1/calculators/batch/runs` computes NOT_STARTED breach state at query time: project a deadline, grade against `now` via `ExpectedRunsService.evaluateSlaStatus` (ON_TIME ≤ deadline < LATE ≤ +bandGap < VERY_LATE). The projection inputs change:

- **New `TimeUtils.businessDaysBetween(LocalDate from, LocalDate to)`** — inverse of `nextBusinessDay`: count business days stepping from `from` to `to` (Fri→Mon = 1, Fri→Tue = 2); returns 0 for `to ≤ from` or nulls.
- [CalculatorStateService.projectSlaTime()](src/main/java/com/company/observability/service/CalculatorStateService.java#L202-L208):
  - **DAILY**: `N = businessDaysBetween(latestRun.getReportingDate(), latestRun.getSlaTime() UTC date)`; if N ≥ 1 use it, else fall back to `parseRunNumber(runNumber)`. Keep the existing time-of-day extraction from the frozen instant. This recovers T+N exactly for both `T+N@HH:mm` and bare-clock runs without persisting the spec. (Duration-derived historical deadlines project best-effort like today — accepted limitation.)
  - **MONTHLY**: the real deadline is start-anchored, so pre-start it can only be estimated: anchor the extracted cutoff on the **estimated start date** (profile/latest-run estimate) with the overnight roll; no estimate → no projected deadline. `buildNotStartedEntry` has `freq` in scope; pass it (or the estimated start) into `projectSlaTime`.
  - `projectSlaTime` needs the latest run's `reportingDate`, so pass the `CalculatorRun` (or both fields) instead of just the `Instant` — `latest` is already in scope at the call site ([line 183-185](src/main/java/com/company/observability/service/CalculatorStateService.java#L183-L185)).
- Execution-date anchoring for the estimates ([CalculatorStateService.java:140](src/main/java/com/company/observability/service/CalculatorStateService.java#L140), [ExpectedRunsService.java:106-107](src/main/java/com/company/observability/service/ExpectedRunsService.java#L106-L107)): use the same derived `N` when a latest run is available, else `parseRunNumber` as today.
- **Bug fix — scope the latest-run lookup by run_number**: [findLatestRunEstimatesByName](src/main/java/com/company/observability/repository/CalculatorRunRepository.java#L617-L632) ignores run_number, so a RUN1 projection typically picks up RUN2's frozen deadline (RUN2 is usually the newest row) → wrong cutoff (21:30 instead of 15:00) → false ON_TIME. Add an overload `findLatestRunEstimatesByName(name, frequency, runNumber)` appending `AND run_number = :runNumber` when runNumber is non-blank; keep the 2-arg signature delegating with null. `CalculatorStateService.buildNotStartedEntry` already has `runNumber` — pass it. `SELECT_BASE` already includes `reporting_date` and `run_number`; no column changes.

### 5. Docs

- `StartRunRequest.slaTime` `@Schema`: describe the three forms + frequency-dependent anchoring (replace the current phase-1/phase-2 text).
- CLAUDE.md "SLA Detection" section + `tech-spec.md`: mode removed, spec formats, MONTHLY anchoring, always-on fallback.

### Explicitly unchanged
`SlaEvaluationService` grading, `LiveSlaBreachDetectionJob`, `SlaMonitoringCache` registration, band config, event flow, caching — all operate on the frozen `sla_time` instant.

### Out of scope (accepted)
- Never-started breach **alerting** (NOT_STARTED grading stays query-time-only; live job only watches started runs). Fixable later via catalogue sync.
- Holiday calendar (business days = weekend skip only).
- Airflow catalogue/DAG changes (their repo; contract documented above).

## Tests

- `SlaBaselineResolverTest` (rework):
  - `T+1@09:30` DAILY, reportingDate = Friday → deadline Monday 09:30 UTC; `T+2@21:30` Wed → Fri 21:30; baselineDurationMs null.
  - `T+1@09:30` MONTHLY → `DomainValidationException`.
  - Bare `09:30` DAILY with runNumber "1"/"2"/null → offsets 1/2/2 (existing tests keep passing).
  - Bare `02:00` MONTHLY, start 23:00 → next-day 02:00; start 01:00 → same-day 02:00.
  - `PT2H30M` any frequency → buffered deadline + baseline ms (existing duration tests, minus `setMode`).
  - Invalid: `T+0@09:30`, `9:30`, `T+1@9:30`, `banana` → `DomainValidationException`.
  - Blank slaTime: request expectedDurationMs → used; else profile avg → used; else ungraded — with no `enabled` flag involved.
- `TimeUtilsTest`: `businessDaysBetween` (Fri→Mon=1, Fri→Tue=2, Mon→Tue=1, same-day=0, to<from=0).
- `CalculatorStateServiceTest`: DAILY projection derives N from latest run's reportingDate→slaTime distance (e.g. latest Tue reporting, deadline Wed = T+1 → project query-date+1biz); run_number-scoped lookup invoked; MONTHLY projection anchors on estimated start.
- `RunIngestionServiceTest` / `AnalyticsServiceTest`: drop `setMode`/`setEnabled`, assert new `resolveExpectedDuration` chain and data-driven reference lines.

## Verification

1. `docker compose up -d`, then `SPRING_PROFILES_ACTIVE=local mvn clean test` — full suite green (config binding will fail fast if any yml/test still references `mode`/`enabled`).
2. Targeted: `mvn test -Dtest=SlaBaselineResolverTest,TimeUtilsTest,CalculatorStateServiceTest,RunIngestionServiceTest,AnalyticsServiceTest`
3. Manual smoke (`SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`, Swagger at `http://localhost:8080/swagger-ui.html`, Basic auth admin/admin + `X-Tenant-Id`):
   - `POST /api/v1/runs/start` DAILY, `slaTime="T+1@09:30"`, `reportingDate` = a Friday → stored/response `slaTime` = Monday 09:30Z.
   - DAILY bare `"09:30"`, `runNumber="2"` → T+2 business days at 09:30Z (unchanged behavior).
   - MONTHLY `"02:00"`, `startTime` 23:00Z → next-day 02:00Z; MONTHLY `"T+1@02:00"` → 400.
   - `"PT2H30M"` → start + 2h30m×1.2 + 15m.
   - `GET /api/v1/calculators/batch/runs` with RUN1 (`T+1@15:00`) and RUN2 (`T+2@21:30`) history, `runNumber=1`, no run today: projected `sla` uses 15:00 from RUN1's own history; after 15:00Z the synthetic NOT_STARTED entry grades `LATE`/`VERY_LATE` with `slaBreached: true`.


## All Code Changes Done (12 files)

```
Core SLA spec parsing

SlaBaselineResolver.java +198/− — replaced global-mode dispatch with self-describing parseSpec() (regex T+N@HH:mm → ISO duration → bare HH:mm → DomainValidationException); new ParsedSpec record; frequency-aware resolve(); always-on blank-fallback chain; deleted enabled gate. Kept parseRunNumber.
Mode/gate removal

SlaMode.java — deleted.
SlaProperties.java — removed mode field + import; fixed slaTimezone javadoc; de-"both modes" the band/lookback javadocs.
DurationBasedSlaProperties.java — removed enabled; kept thresholdPercent.
application.yml — removed observability.sla.mode and duration-based.enabled; updated comments.
Mode-free service equivalents

RunIngestionService.java — dropped SlaMode import; resolveEstimatedEnd now keys on deadline != null; resolveExpectedDuration collapsed to request → profile avg → baseline → null; run.start.persist log now includes slaSpec={}.
AnalyticsService.java — dropped SlaMode import; resolveReferenceLines is data-driven on slaTime != null.
NOT_STARTED projection

TimeUtils.java — new businessDaysBetween(from, to).
CalculatorRunRepository.java — new run_number-scoped findLatestRunEstimatesByName(name, freq, runNumber) overload; 2-arg delegates.
CalculatorStateService.java — run_number-scoped lookup; new deriveOffsetDays; frequency-aware projectSlaTime (DAILY derived-N re-anchor / MONTHLY estimated-start anchor).
ExpectedRunsService.java — derives DAILY T+N offset from the calculator deadline for placeholder anchoring.
Docs/contract

StartRunRequest.java — @Schema rewritten for the three forms.
Tests (5 files)
SlaBaselineResolverTest.java — fully reworked into form-based groups.
TimeUtilsTest.java — new BusinessDaysBetween class.
CalculatorStateServiceTest.java — 3-arg stubs + derived-N / run_number-scoping / MONTHLY-anchoring tests.
RunIngestionServiceTest.java — dropped setMode; assert new expected-duration chain.
AnalyticsServiceTest.java — dropped durationModeProperties; assert data-driven reference lines.

```
