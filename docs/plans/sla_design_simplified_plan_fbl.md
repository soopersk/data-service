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