# Task: Duration-Based (Average-Runtime) SLA

Source plan: `~/.claude/plans/review-the-current-implementation-virtual-quasar.md`

## Key decisions (confirmed with user)
- Multi-frequency calculators exist (a calculator_id can have both DAILY and MONTHLY runs).
- `frequency` must join the **unique key** of `calculator_sli_daily` (a plain index can't serve `ON CONFLICT` nor prevent DAILY/MONTHLY blending on month-end dates). Since this is a derived aggregate table (not the source of truth), the PRIMARY KEY was simply widened to the 4 columns `(calculator_id, tenant_id, frequency, reporting_date)`, with `frequency NOT NULL DEFAULT 'DAILY'` so future no-frequency calculators default cleanly.
- Migration version is **V8** (actual migrations are V1–V7; plan said "V12 — verify").
- Scope-control: existing analytics reads (`findRecentAggregates`/`findByReportingDates`) **collapse** rows per date (SUM across frequency) so `/runtime`, `/trends`, `/sla-summary` keep identical behavior — no controller ripple. The resolver gets a dedicated frequency-aware `findAverageDuration`.

## Plan
- [ ] V8 migration: add `frequency`, drop 3-col PK, add 4-col unique index, backfill from calculator_runs
- [ ] `DurationBasedSlaProperties` + `observability.sla.duration-based.*` in application.yml
- [ ] `DailyAggregateRepository`: frequency-aware upsert; collapse reads; new `findAverageDuration`
- [ ] `SlaBaselineResolver` (new): fallback chain avg → expectedDurationMs → slaTime-budget → null; derived deadline
- [ ] `SlaEvaluationService`: duration-band classification off frozen `slaTime`; FAILED/TIMEOUT → CRITICAL
- [ ] `StartRunRequest.slaTime` optional (remove @NotNull)
- [ ] `RunIngestionService.doStartRun`: resolver call; remove breach-at-start; drop DAILY-only gates; pass frequency to aggregate
- [ ] `LiveSlaBreachDetectionJob.determineSeverity`: grade by minutes past derived deadline using band gap
- [ ] Tests: SlaBaselineResolverTest, SlaEvaluationServiceTest, RunIngestionServiceTest, DailyAggregateRepositoryJdbcTest, LiveSlaBreachDetectionJobTest
- [ ] Build + full test run

## Review

### Done
- All 10 implementation tasks complete. Code compiles (`mvn test-compile` clean).
- Unit test run: **295 run, 0 failures**, 29 skipped (Testcontainers, Docker down).
- New/updated unit tests green: SlaBaselineResolverTest, SlaEvaluationServiceTest,
  RunIngestionServiceTest, LiveSlaBreachDetectionJobTest.
- The 2 "errors" are `CalculatorRunRepositoryDimensionalTest` (a pre-existing
  `@SpringBootTest @ActiveProfiles("local")`) failing on `Connection to localhost:5432
  refused` — Docker is down. Unrelated to this change.

### Plan deviations / decisions
- Migration is **V8** (plan said V12; actual migrations were only V1–V7).
- `frequency` joined the **4-col PRIMARY KEY** (the old 3-col PK was widened). A plain
  index can't arbitrate ON CONFLICT nor prevent the month-end blend; since this is a
  derived aggregate table, widening its PK is the clean fix. Default 'DAILY' for
  no-frequency calcs.
- Existing analytics reads (`findRecentAggregates`/`findByReportingDates`) **collapse**
  across frequency (GROUP BY) so `/runtime`, `/trends`, `/sla-summary` keep identical
  behavior — avoided threading frequency through their controllers (scope control).
  `getRuntimeAnalytics` was intentionally left frequency-blended (pre-existing behavior),
  not made frequency-specific — orthogonal to this feature.

### NOT yet verified (needs Docker)
- V8 Flyway migration against real Postgres (syntax + backfill recompute).
- `DailyAggregateRepositoryJdbcTest` new frequency-separation / findAverageDuration tests.
- Integration of start→complete band grading end-to-end.
- Run after `docker compose up -d`: `SPRING_PROFILES_ACTIVE=local mvn test`.

---

# Task 2: Precompute-Once / Cache-Reuse for Calculator Profiles

Source plan: `~/.claude/plans/hidden-nibbling-lantern.md` (approved).

## What changed
- **EOD aggregation:** `calculator_sli_daily` is no longer written per run completion.
  A nightly `DailyAggregationJob` (`@Scheduled` cron `0 30 0 * * *`) recomputes a trailing
  window from `calculator_runs` (idempotent DELETE+INSERT) and warms the profile cache.
- **CalculatorProfile cache:** new `CalculatorProfileService` (Redis cache-aside,
  `obs:profile:{calc}:{tenant}:{freq}`, TTL 26h / 60m empty sentinel). Removes the per-run-start
  DB query.
- **One profile serves both:** SLA baseline (`SlaBaselineResolver` now takes the profile in) and
  estimated start/end fallback in `RunIngestionService`.
- **Estimated start/end precedence:** request value → cached profile (avg start / avg duration)
  → computed (start + expectedDurationMs) → null. Added `StartRunRequest.estimatedEndTime`.
- **`/executions` envelope reference lines** now sourced from the cached profile (stable typical
  start + buffered deadline), falling back to latest-run values when samples insufficient.
  `/run-performance` and per-run values untouched.
- **Removed:** `DailyAggregateRepository.upsertDaily`, `RunIngestionService.updateDailyAggregate`,
  `AverageDuration` record (replaced by `findProfile`/`findAllProfiles` returning `CalculatorProfile`).

## Verification
- `mvn test-compile` clean. `mvn test`: **301 run, 0 failures**, 28 skipped (Testcontainers, Docker down).
- New/updated unit tests green: CalculatorProfileServiceTest, SlaBaselineResolverTest,
  RunIngestionServiceTest (estimate precedence), AnalyticsServiceTest (reference lines), DailyAggregationJobTest.
- The 2 errors remain the pre-existing `CalculatorRunRepositoryDimensionalTest` (`localhost:5432 refused`, Docker down).

### NOT yet verified (needs Docker)
- `DailyAggregateRepositoryJdbcTest` rewrite (recompute idempotency, findProfile/findAllProfiles).
- `DailyAggregationJob` end-to-end recompute + warm against real Postgres/Redis.
- No schema migration in this task (uses existing V8 columns).

---

# Task 3: Redis Caching for Primary Query APIs (batch/runs + executions)

Source plan: `C:\Users\SOOPER\.claude\plans\review-the-two-main-clever-valley.md`
**Branch:** main (dev, no backward-compat concerns)
**Status as of 2026-05-23:** All code changes complete — **test suite not yet run**

## What changed

### run_number relaxation (both endpoints)
- `RunQueryController` — removed `@Pattern(regexp="^[12]$")` from `run_number` param
- `AnalyticsController` — removed `@Pattern` + orphaned `import jakarta.validation.constraints.Pattern`
- `CalculatorStateService.getState()` — normalize blank→null (`rn`) before repo call and cache key
- `AnalyticsService.getRunExecutionsByName()` — normalize blank→null (`rn`) before repo call and cache key

### /executions → AnalyticsCacheService
- `AnalyticsCacheService` — added `RUN_EXECUTIONS_CACHE_PREFIX` constant; two new overloads: `getFromCache(prefix, calcKey, freq, days, runNumber, Class)` and `putInCache(prefix, calcKey, freq, days, runNumber, Object)`. Key: `obs:analytics:executions:{name}:{freq}:{days}:{rn|all}`. New `buildKeyWithRunNumber` private builder.
- `AnalyticsCacheService.evictForCalculator/evictForCalculatorByPrefix` — refactored to evict under **both** `calculatorId` index and `calculatorName` index (executions uses name-keyed entries). New helpers: `evictIndex`, `evictIndexByPrefix`.
- `AnalyticsCacheService.onRunStarted` — now evicts both `RUN_PERF_CACHE_PREFIX` and `RUN_EXECUTIONS_CACHE_PREFIX`
- `AnalyticsService.getRunExecutionsByName()` — wrapped in cache get/put using `CACHE_EXECUTIONS` prefix
- `ObservabilityConstants` — added `CACHE_ANALYTICS_MISS` was already present; confirmed used

### /batch/runs → new CalculatorStateCacheService
- New `cache/CalculatorStateCacheService.java` — key `obs:state:{name}:{date}:{freq}:{rn|all}`, state-aware TTL tiers (RUNNING→30s / NOT_STARTED→60s / terminal+failures→5min / terminal clean→4h), bulk `getEntries` + `putEntries`, `determineTtl`, best-effort Redis ops
- `ObservabilityConstants` — added `CACHE_STATE_HIT = "obs.cache.state.hit"` and `CACHE_STATE_MISS = "obs.cache.state.miss"`
- `CalculatorStateService` — added `CalculatorStateCacheService stateCache` constructor field; `getState()` now does full read-through/write-through (partial miss supported, empty-runs entries cached with 60s TTL)

### Tests
- **NEW** `CalculatorStateCacheServiceTest` — 8 tests: TTL tiers (5), get/put round-trip, miss returns empty, Redis failure swallowed (get + put)
- **Updated** `CalculatorStateServiceTest` — constructor now takes 3 args; 4 new cache tests: full hit skips DB, partial miss queries DB only for misses, absent calculator cached empty, blank runNumber normalised to null
- **Updated** `AnalyticsServiceTest` — 4 new tests: executions cache hit skips DB, cache miss populates cache, runNumber distinguishes keys, blank runNumber normalised to null
- **Updated** `AnalyticsCacheServiceTest` — 3 new eviction tests replacing old ones: `onRunStarted` evicts run-perf+executions prefixes from both id/name indexes; `onRunCompleted` full eviction of both; `onSlaBreached` full eviction of both

## Remaining step

- [ ] Run test suite — fix any failures:
  ```bash
  docker compose up -d
  SPRING_PROFILES_ACTIVE=local mvn clean test
  ```

### Likely failure points to check
1. **`AnalyticsCacheServiceTest`** — new eviction tests verify `redisTemplate.delete(...)` is called with specific key lists for both id-index and name-index. The run fixture has `calculatorId="calc-1"` and `calculatorName="Calculator"` — they differ, so both indexes are evicted. If a test fails, confirm the run helper sets the name.
2. **`CalculatorStateServiceTest`** partial-miss test — uses `eq(List.of("other"))` as the argument matcher for the repo call. If Mockito complains, confirm the `missNames` list is computed correctly (only names absent from the cache map).
3. **`AnalyticsServiceTest`** executions tests — mock `cacheService.getFromCache` with 6-arg overload. Confirm overload matches `getFromCache(String, String, String, int, String, Class<T>)`.

## Verification steps (manual, after tests pass)
1. `docker compose up -d` + `SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`
2. POST `runs/start` → seed a run. Call each endpoint twice:
   - `GET /api/v1/calculators/batch/runs?reporting_date=...&frequency=DAILY&keys=cap`
   - `GET /api/v1/analytics/calculators/cap/executions?days=30`
3. 2nd call = cache hit: `obs.cache.state.hit` / `obs.cache.analytics.hit` increments on `/actuator/prometheus`; DB query counters do **not** increment
4. `redis-cli KEYS "obs:state:*"` and `KEYS "obs:analytics:executions:*"` → entries present with expected TTLs
5. POST `runs/{id}/complete` → executions cache evicted (event-driven, seconds); batch/runs cache heals within ≤30s (TTL)
6. `?run_number=` (empty) → all runs returned (not 400); `?run_number=3` → filtered (not 400)
