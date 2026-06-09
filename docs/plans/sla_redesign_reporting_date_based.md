# SLA Deadline Resolution Architecture — Capital Calculator Group

## Context

Capital Calculator Group runs 10 regional calculators (`capitalcalcdev`, `capitalcalcdevmedium`,
`capitalcalcdevsmall`) across regions [WMAP, WMDE, ASIA, WMUS, AUNZ, WMCH, ZURI, LDNL, AMER, EURO]
for a given `reporting_date` + `run_number`. The challenge is: given a fixed-clock SLA time (e.g.,
`"09:30"`) configured at the calculator-group level, how do we derive a deterministic absolute UTC
`Timestamp` deadline that is correct for all 10 regions regardless of when they individually check in?

The current `SlaBaselineResolver` in `CLOCK_TIME` mode anchors the date to `LocalDate.from(startTime)`.
This plan evaluates two proposed alternatives and recommends a clean, surgical replacement.

---

## Step 1: Critical Evaluation of Both Approaches

### Approach 1 — "Date From First Regional Run's Start Time" (current system, extended)

The concept: when the very first regional run POSTs to `/start`, extract `LocalDate.from(startTime)`,
append the SLA clock time, and either cache this group-level deadline or let each run independently
derive it the same way from their own `startTime`.

#### Failure Modes

| Scenario | What Happens | Severity |
|---|---|---|
| WMAP starts at **23:55 UTC**, SLA is `"09:30"` | Deadline = `09:30 today` — 14+ hrs in the past. All 10 regions are pre-breached before they run. | **Critical** |
| Airflow re-triggers a failed WMAP run at **01:00 UTC** the next day | Date anchor shifts to next calendar day. Deadline diverges from the 9 regions that ran on the prior day's date. | **Critical** |
| Two regions start simultaneously (race) | If sharing a cached group deadline, last-writer-wins; one thread may read stale cache. | **High** |
| `capitalcalcdevsmall` (a split run) is the "first" to check in | This lightweight shard anchors the deadline. If it starts earlier than the main `capitalcalcdev`, date could be correct by luck — not by design. | **Medium** |
| SLA is overnight — `"01:30"` UTC | The `+1 day` rollover guard in `clockTimeDeadlineUtc()` fires per-run and is tied to `startTime`, so a late-starting EURO run at `02:00` resolves deadline to the NEXT day while early-starting WMAP at `23:00` resolved to the PREVIOUS night. Two different deadlines for the same logical SLA. | **Critical** |
| Weekend: `reporting_date = Friday`, all runs start Saturday | Deadline = `Saturday 09:30`, but the business expectation is `Monday 09:30`. | **High** |

**Root cause:** `startTime` is an operational fact (when Airflow happened to trigger). It is NOT a
business fact. Using it as a date anchor couples a business constraint to operational noise.

---

### Approach 2 — "Business Window: `reporting_date` + `run_number`" (proposed)

The concept: compute the absolute execution target date as a pure function of the business metadata
already present on every run:

```
execution_date = nextBusinessDay(reporting_date, run_number)
deadline       = execution_date + sla_clock_time (UTC/CET)
```

- `run_number = 1` → T+1 (next business day after `reporting_date`)
- `run_number = 2` → T+2

#### Edge Case Analysis

| Scenario | Behaviour | Assessment |
|---|---|---|
| WMAP starts at `23:55 UTC` | `reporting_date` and `run_number` are unchanged. Deadline = next business day `09:30`. | ✅ Correct |
| Airflow re-triggers at `01:00 UTC next day` | Same `reporting_date` + `run_number` → same deadline, always. | ✅ Correct |
| `reporting_date = Friday`, `run_number = 1` | `nextBusinessDay(Friday, 1)` = Monday. Deadline = `Monday 09:30`. | ✅ Correct |
| `reporting_date = Friday`, `run_number = 2` | `nextBusinessDay(Friday, 2)` = Tuesday. Deadline = `Tuesday 09:30`. | ✅ Correct |
| Public holiday on Monday | Business calendar skips Monday → `nextBusinessDay(Friday, 1)` = Tuesday. | ✅ Correct (with calendar) |
| Split-run `capitalcalcdevsmall` checks in first | Uses its own `reporting_date` + `run_number` → same deadline as every other split/region. | ✅ Correct |
| NOT_STARTED synthetic entries | Can compute deadline before ANY run starts — needs only `reporting_date` + `run_number` from config. | ✅ Correct |
| Overnight SLA clock time (`"01:30"`) | Deadline = `execution_date 01:30 UTC` — no per-run rollover guard needed. Date is fixed. | ✅ Correct |

**Verdict: Approach 2 is unambiguously superior.** It transforms an inherently stateful, race-prone
question ("what did the first run's date happen to be?") into a stateless deterministic function.

---

## Step 2: The Elegant Architecture

### Design Principle

> A SLA deadline is a **business fact**, not an operational measurement.
> It must be derivable from business inputs alone, without observing run start times.

### The Single Change Point

The current `SlaBaselineResolver.clockTimeDeadlineUtc()` (and the call site in the resolver's
`resolve()` method) anchors to `startTime`. The minimal, elegant fix is to replace that one
date-anchor with the deterministic `nextBusinessDay(reportingDate, runNumber)` call.

No new domain objects, no new cache keys, no group-level shared state, no distributed locks.
The deadline computation remains a pure function in `SlaBaselineResolver`.

### Architecture Diagram

```
StartRunRequest
  ├── reportingDate: 2026-05-21 (Friday)
  ├── runNumber: "1"
  └── slaTime: "09:30"           ← from Airflow (clock-time string)
            │
            ▼
  SlaBaselineResolver.resolve()
            │
            ├── CLOCK_TIME mode
            │     ├── parse "09:30" → LocalTime
            │     ├── runNumber → int n (default 2 if absent/null)
            │     ├── nextBusinessDay(reportingDate, n) → 2026-05-25 (Monday)
            │     └── deadline = 2026-05-25T09:30:00Z  ← frozen, deterministic
            │
            └── DURATION mode (unchanged — not affected)

  Frozen deadline stored in calculator_runs.sla_time (TIMESTAMPTZ)
  Same for all 10 regional runs of this reporting_date + run_number
```

### What Does NOT Change

| Component | Status |
|---|---|
| `StartRunRequest` | No changes — `reportingDate` and `runNumber` already exist |
| `CalculatorRun` domain | No changes — `slaTime` field is the frozen deadline |
| `SlaEvaluationService` | No changes — evaluates against the frozen deadline |
| `LiveSlaBreachDetectionJob` | No changes — reads frozen deadline from Redis |
| `SlaMonitoringCache` | No changes |
| DB schema / Flyway | No changes |
| Regional breach evaluation | No changes — each run stores its own `sla_breached` boolean |

### The Breach Evaluation Per Region (already correct)

The existing architecture already evaluates SLA **per run** — each region's `calculator_run` row
stores its own `sla_breached=true/false` and `sla_band`. Nothing needs to change here.
The fix in this plan ensures all 10 regions receive the **same correct** `slaTime` deadline
(currently they may get different values if start times differ, or a wrong date if a run starts
near midnight). Once the deadline is deterministic, per-region breach evaluation is trivially correct.

---

## Step 3: Schema & Configuration Layout

### SLA Configuration at Calculator-Group Level (application.yml)

```yaml
observability:
  sla:
    mode: CLOCK_TIME

  calculator-groups:
    capital:
      sla-clock-time: "09:30"        # HH:mm in the configured timezone
      sla-timezone: "UTC"            # or "Europe/London" for CET-aware SLAs
      members:                       # Real calculator names in this group
        - capitalcalcdev
        - capitalcalcdevmedium
        - capitalcalcdevsmall
        - capitalcalcextrasmall
    modelled-exposure:
      sla-clock-time: "14:00"
      sla-timezone: "UTC"
      members:
        - modelled-exposure-regular
        - modelled-exposure-reruns
```

> **Note:** If Airflow already sends `slaTime: "09:30"` on the `StartRunRequest`, the service-side
> config is optional (a validation/override layer). In the simplest form, Airflow is the source of
> truth for the clock time string, and the service only changes HOW it resolves the calendar date.

### Minimal `StartRunRequest` Data Contract (no changes required)

```json
{
  "runId": "cap-wmap-20260521-run1",
  "calculatorId": "3f7b2a...",
  "calculatorName": "capitalcalcdev",
  "frequency": "DAILY",
  "reportingDate": "2026-05-21",
  "startTime": "2026-05-24T22:55:00Z",
  "runNumber": "1",
  "region": "WMAP",
  "slaTime": "09:30"
}
```

**Key fields for deadline resolution:**
- `reportingDate` — business cycle date (Friday in this example)
- `runNumber` — `"1"` = T+1, `"2"` = T+2 (Airflow must send this consistently)
- `slaTime` — clock time string (`"09:30"`, already HH:mm format in current CLOCK_TIME mode)
- `startTime` — **no longer used for date anchor** (still stored, used for duration calc, breach timing)

### `/batch/runs` Query Contract (minor addition)

Add `run_number` as an optional query parameter (defaults to `"2"` — mirrors the null-default
in `calculator_runs`):

```
GET /api/v1/calculators/batch/runs
  ?keys=capital
  &reporting_date=2026-05-22
  &frequency=DAILY
  &run_number=2          ← NEW (optional, default "2")
```

**Why this is necessary:** For fully NOT_STARTED entries (no runs have checked in yet for this
`reporting_date`), the service must project the SLA deadline using `nextBusinessDay(reportingDate,
runNumber)`. With no actual runs to inspect, `runNumber` must come from the request.
The caller (dashboard BFF, Airflow UI) always knows which run cycle it is displaying.

Without this param, the service cannot distinguish "show me T+1 status" from "show me T+2 status"
when zero runs have started — it would silently default to T+2 for `sla` projection.

### Holiday Calendar Configuration

```yaml
observability:
  business-calendar:
    # Public holidays to skip (format: YYYY-MM-DD).
    # Supplement with region-specific calendars if needed.
    holidays:
      - "2026-01-01"   # New Year's Day
      - "2026-04-03"   # Good Friday
      - "2026-04-06"   # Easter Monday
      - "2026-05-04"   # Early May Bank Holiday
      - "2026-05-25"   # Spring Bank Holiday
      - "2026-08-31"   # Summer Bank Holiday
      - "2026-12-25"   # Christmas Day
      - "2026-12-28"   # Boxing Day (observed)
```

> A simple `Set<LocalDate>` loaded from YAML is sufficient. No heavy calendaring library (Joda,
> ThreeTen-Extra) is required unless multi-jurisdiction holiday handling is needed.

---

## Step 4: Logic & Algorithm

### Core Function: `nextBusinessDay(date, n)`

```python
def next_business_day(date: LocalDate, n: int, holidays: Set[LocalDate]) -> LocalDate:
    """
    Advance `date` by `n` business days, skipping weekends and configured holidays.
    n=1 → T+1, n=2 → T+2
    """
    result = date
    steps_taken = 0
    while steps_taken < n:
        result = result + timedelta(days=1)
        if result.weekday() < 5 and result not in holidays:   # Mon–Fri, not a holiday
            steps_taken += 1
    return result
```

**Edge cases handled:**
- `reporting_date = Friday` + `n=1` → skips Sat/Sun → Monday ✅
- `reporting_date = Friday` + holiday on Monday → skips to Tuesday ✅
- `n=0` → same date (unusual; guards against null/missing run_number) ✅

---

### Core Function: `resolve_clock_time_deadline(request, holidays)`

```python
def resolve_clock_time_deadline(
    reporting_date: LocalDate,
    run_number: str | None,
    sla_clock_time: str,          # "HH:mm" or "HH:mm:ss"
    sla_timezone: ZoneId,
    holidays: Set[LocalDate]
) -> Instant:
    """
    Returns the absolute UTC deadline for a run.
    Fully deterministic — independent of startTime.
    """
    # 1. Parse the clock time (e.g., "09:30" → LocalTime(9, 30))
    clock_time = LocalTime.parse(sla_clock_time)                  # HH:mm

    # 2. Resolve run_number → integer offset (default to 2 if absent/null — matches calculator_runs convention)
    n = int(run_number) if run_number and run_number.isdigit() else 2

    # 3. Compute execution target date via business calendar
    execution_date = next_business_day(reporting_date, n, holidays)

    # 4. Combine date + clock time in the configured timezone → UTC instant
    deadline = ZonedDateTime.of(execution_date, clock_time, sla_timezone).toInstant()

    return deadline
```

### Breach Evaluation Per Region (unchanged logic)

```python
def evaluate_sla_breach(
    run: CalculatorRun,
    now: Instant,
    band_gap_ms: int
) -> SlaEval:
    """
    Per-region evaluation — called at completion (SlaEvaluationService)
    and periodically for live detection (LiveSlaBreachDetectionJob).
    Each region independently evaluates against the same frozen deadline.
    """
    if run.sla_time is None:
        return SlaEval(sla_status="UNGRADED", sla_breached=False)

    deadline = run.sla_time                         # Instant — frozen at /start
    delta_ms = (now - deadline).total_milliseconds

    if delta_ms <= 0:
        return SlaEval(sla_status="ON_TIME", sla_breached=False)
    elif delta_ms <= band_gap_ms:
        return SlaEval(sla_status="LATE", sla_breached=True)
    else:
        return SlaEval(sla_status="VERY_LATE", sla_breached=True)

# Result: EURO can be sla_breached=True while WMAP is sla_breached=False.
# All 10 regions share the same deadline Instant — only their completion time differs.
```

### projectSlaTime — Re-anchoring Historical Deadlines for NOT_STARTED Entries

For the NOT_STARTED synthetic entry case (from the previous plan), the `projectSlaTime` helper must
also use the business-calendar formula — NOT the naive `timeOfDay % 86400` modulo:

```python
def project_sla_time(
    historical_sla_time: Instant,
    target_reporting_date: LocalDate,
    run_number: str | None,
    sla_timezone: ZoneId,
    holidays: Set[LocalDate]
) -> Instant:
    """
    Re-anchors a historical frozen SLA deadline to a new reporting date.
    Used when no runs have started today and we need a synthetic SLA for display.
    """
    # Extract only the time-of-day component from the historical deadline
    clock_time = historical_sla_time.atZone(sla_timezone).toLocalTime()
    n = int(run_number) if run_number and run_number.isdigit() else 2
    execution_date = next_business_day(target_reporting_date, n, holidays)
    return ZonedDateTime.of(execution_date, clock_time, sla_timezone).toInstant()
```

> The previous plan used `timeOfDayMs = historical.toEpochMilli() % 86_400_000L` — this is wrong
> when `sla_timezone` is CET (DST offset shifts the UTC epoch modulo). Always extract time-of-day
> in the configured timezone, not raw UTC epoch arithmetic.

---

## Files to Modify

### 1. `SlaBaselineResolver.java` (primary — deadline logic)

**Current date-anchor logic** (inside `clockTimeDeadlineUtc` or equivalent):
```java
LocalDate runDate = startTime.atZone(ZoneOffset.UTC).toLocalDate();
ZonedDateTime deadline = ZonedDateTime.of(runDate, parsedSlaTime, ZoneOffset.UTC);
if (!deadline.toInstant().isAfter(startTime)) {
    deadline = deadline.plusDays(1);   // overnight rollover guard
}
```

**Replacement:**
```java
int n = parseRunNumber(request.getRunNumber());   // default 2 (null coalesces to 2, matching calculator_runs convention)
LocalDate executionDate = businessCalendar.nextBusinessDay(request.getReportingDate(), n);
ZonedDateTime deadline = ZonedDateTime.of(executionDate, parsedSlaTime, slaTimezone);
// No rollover guard needed — date is business-calendar-derived, not start-time-derived
```

**New dependency:** `BusinessCalendarService` (a single `@Component` with `Set<LocalDate> holidays`
loaded from config and a `nextBusinessDay(LocalDate, int)` method — ~30 lines).

### 2. `CalculatorStateService.java` (secondary — NOT_STARTED projection)

- `projectSlaTime()`: replace epoch-modulo with timezone-aware `ZonedDateTime` extraction (DST fix)
- `buildNotStartedEntry()` and `entryWithSyntheticRun()`: thread `runNumber` parameter through so
  the business-calendar formula receives the correct offset
- `buildNotStartedEntry()` receives `runNumber` from `CalculatorStateService.getState()` which
  receives it from the controller

### 3. `RunQueryController.java` + `CalculatorRunRepository.java` (run_number filter + wildcard)

**Controller:** Add `@RequestParam(defaultValue = "2") String runNumber` to `getBatchRuns()`.
Pass it down the call chain to `CalculatorStateService.getState()`.
Default `"2"` matches the `COALESCE(run_number, '2')` convention in `calculator_runs`.

**Two run_number semantics must be respected in the DB query:**

| Calculator type | `run_number` in DB | Behaviour |
|---|---|---|
| `capital` (and cycle-specific calcs) | `"1"` or `"2"` | Returned only when query `run_number` matches |
| `modelled-exposure`, `gemini-hedge` | `null` | **Wildcard — returned for any `run_number` query** |

**Repository query change** — wherever `calculator_runs` is filtered by `run_number` for
the `/batch/runs` endpoint:
```sql
-- Old (excludes null-run_number runs entirely):
WHERE run_number = :runNumber

-- New (null = cycle-agnostic, always included):
WHERE (run_number = :runNumber OR run_number IS NULL)
```

This does NOT affect SLA deadline or profile aggregation: null-run_number runs still use
`COALESCE(run_number, '2')` for those two purposes, as the SLA deadline is frozen at `/start`
time and doesn't change across dashboard views.

### 4. New: `BusinessCalendarService.java` (~30 lines)

A single `@Component` with:
- `Set<LocalDate> holidays` loaded from `application.yml`
- `LocalDate nextBusinessDay(LocalDate start, int n)` — the pure function shown in Step 4

### 5. `DailyAggregateRepository.java` + Flyway migration (profile per run_number)

**Why:** Capital's 10 regions run throughout the day at different times depending on `run_number`.
A single blended profile gives a misleading `estimatedStartTime` for NOT_STARTED entries.

**Schema change** (new Flyway migration `V_next__calculator_sli_daily_run_number.sql`):
```sql
ALTER TABLE calculator_sli_daily ADD COLUMN run_number VARCHAR(10) NOT NULL DEFAULT '2';
-- Backfill: existing rows had no run_number → default to '2'
-- New PK: (calculator_name, frequency, reporting_date, run_number)
ALTER TABLE calculator_sli_daily DROP CONSTRAINT calculator_sli_daily_pkey;
ALTER TABLE calculator_sli_daily ADD PRIMARY KEY (calculator_name, frequency, reporting_date, run_number);
```

**Aggregation SQL change** — two-pass UNION in `recomputeForDateRange()`:

```sql
-- Pass 1: explicit run_number rows (capital and other cycle-specific calcs)
SELECT
    calculator_name, frequency, reporting_date,
    run_number,
    COUNT(*) AS total_runs, ...
FROM calculator_runs
WHERE end_time IS NOT NULL
  AND run_number IS NOT NULL
  AND reporting_date BETWEEN :from AND :to
GROUP BY calculator_name, frequency, reporting_date, run_number

UNION ALL

-- Pass 2: null-run_number rows (modelled-exposure, gemini-hedge) — fanned out into BOTH buckets
SELECT
    cr.calculator_name, cr.frequency, cr.reporting_date,
    rn.run_number,
    COUNT(*) AS total_runs, ...
FROM calculator_runs cr
CROSS JOIN (VALUES ('1'), ('2')) AS rn(run_number)
WHERE cr.end_time IS NOT NULL
  AND cr.run_number IS NULL
  AND cr.reporting_date BETWEEN :from AND :to
GROUP BY cr.calculator_name, cr.frequency, cr.reporting_date, rn.run_number
```

Result: `calculator_sli_daily` gets one row per explicit run_number AND two rows (for '1' and '2')
for every null-run_number calculator. No COALESCE, no fallback chain.

**CalculatorProfileService** — add overload:
```java
// Existing (unchanged — backward-compatible, used by analytics and non-cycle-aware callers)
public CalculatorProfile getProfile(String name, Frequency freq)

// New overload — used by CalculatorDimensionService and CalculatorStateService
public CalculatorProfile getProfile(String name, Frequency freq, String runNumber)
    // Redis key: obs:profile:{name}:{frequency}:{runNumber}
    // For null-run_number calcs: data exists in BOTH '1' and '2' buckets — no fallback needed
    // Only falls back to getProfile(name, freq) for brand-new calcs with zero history
```

**CalculatorDimensionService and CalculatorStateService** — pass `runNumber` to `getProfile()`.
The `runNumber` is already available from the `/batch/runs?run_number=` query param.

### 6. Configuration: `application.yml` / `SlaProperties.java`

Add `slaTimezone` (default `UTC`) and `businessCalendar.holidays` list.

---

## Assumptions

```
CONFIRMED FACTS:
1. `run_number` is already a column in `calculator_runs`.
2. Two distinct null semantics:
   - For SLA deadline resolution: null → coalesced to "2" (T+2) via parseRunNumber()
   - For /batch/runs query filtering: null → wildcard (WHERE run_number = :n OR run_number IS NULL)
   - For profile aggregation: null-run_number runs are written into BOTH '1' AND '2' buckets
     via CROSS JOIN expansion in DailyAggregationJob — no fallback chain needed
   - Calculators using null-as-wildcard: modelled-exposure, gemini-hedge (cycle-agnostic)
   - Calculators using explicit run_number: capital (and other cycle-specific calcs)
3. `run_number` semantics: "1" = T+1, "2" = T+2 (confirmed).
4. The SLA clock time is sent from Airflow on each StartRunRequest.slaTime (HH:mm string).

REMAINING ASSUMPTIONS:
A. The business calendar (weekends + holidays) applies uniformly to all calculator groups.
   Multi-jurisdiction support is a future extension.
B. Airflow sends run_number consistently for all 10 regional runs of the same logical batch.
→ Correct assumption B if Airflow behaviour differs.
```

---

## Verification

1. **Friday reporting_date, run_number=1:** POST `/start` for all 10 regions with
   `reportingDate=2026-05-22` (Friday), `runNumber="1"`, `slaTime="09:30"`.
   Verify all 10 `calculator_runs.sla_time` = `2026-05-25T09:30:00Z` (Monday).

2. **Late-night start, no false breach:** POST `/start` with `startTime=2026-05-22T23:55:00Z`,
   `reportingDate=2026-05-22`, `runNumber="1"`. Verify `sla_time = 2026-05-25T09:30:00Z`,
   NOT `2026-05-22T09:30:00Z` (which is 14+ hours in the past).

3. **Holiday skipping:** POST with `reportingDate=2026-05-22` (Friday before May Bank Holiday Monday).
   Verify deadline lands on Tuesday `2026-05-26`.

4. **Regional independence:** Start all 10 regions at different times across the day.
   Verify all 10 `sla_time` values are identical.
   Complete EURO late → verify `EURO.sla_breached=true`, other 9 `sla_breached=false`.

5. **NOT_STARTED synthetic entries:** Call `GET /batch/runs?keys=capital&reporting_date=2026-05-22`
   before any run starts. Verify `sla` field on all 10 synthetic entries = `2026-05-25T09:30:00Z`.

6. **CET timezone SLA:** Configure `slaTimezone=Europe/London`, `slaClockTime="09:30"`.
   In winter (UTC+0), deadline = `09:30 UTC`. In summer (UTC+1 / BST), deadline = `08:30 UTC`.
   Verify the `projectSlaTime` re-anchor also reflects the DST offset correctly.

---

## Files changed:

All tasks complete. Here's a summary of what was implemented:

**Batch 1 — Core SLA fix (business-calendar deadline):**

```
BusinessCalendarProperties.java — loads holidays from application.yml
BusinessCalendarService.java — nextBusinessDay(LocalDate, int) skipping weekends + holidays
SlaProperties.java — added slaTimezone (default "UTC")
application.yml — added observability.business-calendar.holidays (2026 UK bank holidays) and sla.sla-timezone
SlaBaselineResolver.java — CLOCK_TIME mode now uses nextBusinessDay(reportingDate, runNumber) instead of startTime date anchor. parseRunNumber() helper defaults to 2 (T+2)
```


**Batch 2 — NOT_STARTED SLA projection:**

```
CalculatorStateService.java — runNumber threaded through buildEntry → buildNotStartedEntry → entryWithSyntheticRun; projectSlaTime() added for re-anchoring historical frozen deadlines with DST-correct timezone extraction
```


**Batch 3 — Profile per run_number:**

```
V8__calculator_sli_daily_run_number.sql — adds run_number column with 4-column PK
DailyAggregateRepository.java — two-pass UNION recompute (explicit + null-run_number CROSS JOIN); findProfileByRunNumber() and findAllProfilesByRunNumber() overloads
CalculatorProfile.java — added runNumber field (nullable)
CalculatorProfileService.java — getProfile(name, freq, runNumber) overload; warm() uses runNumber in cache key
DailyAggregationJob.java — warms both blended and run_number-scoped profiles nightly
CalculatorDimensionService.java — padDimensions() accepts runNumber, uses scoped profile
RunQueryController.java — passes runNumber to padDimensions()
```
