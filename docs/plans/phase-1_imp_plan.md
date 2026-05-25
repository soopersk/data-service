# Phase-1 Implementation Plan

## Context
Phase-1 targets the minimal viable slice: ingest run lifecycle events from Airflow, expose two dashboard-facing query endpoints (`GET /batch/runs` and `GET /executions`), and evaluate SLA at completion time via a nightly aggregation job. Live SLA detection (Redis sorted-set polling, `LiveSlaBreachDetectionJob`) is explicitly out of scope.

This plan is derived from the existing fully-implemented codebase — treat it as the canonical reference for each class.

---

## Scope Constraints
| In scope | Out of scope (Phase-2+) |
|---|---|
| `POST /runs/start` + `POST /runs/{runId}/complete` | Live SLA detection (`LiveSlaBreachDetectionJob`, `SlaMonitoringCache`) |
| `GET /batch/runs` | Analytics endpoints (`/runtime`, `/sla-summary`, `/trends`, `/sla-breaches`, `/run-performance`) |
| `GET /{name}/executions` | `RunQueryService` status/history endpoints |
| Nightly `DailyAggregationJob` + SLA on-write evaluation | Alert delivery / `AlertHandlerService` |
| Redis caching for batch/runs and executions | Partition maintenance (`MaintenanceController`) |

---

## Implementation Order (dependency-first)

### Layer 0 — Database Schema
Flyway migrations — implement in order; later layers depend on these tables.

| Migration | Creates | Required by |
|---|---|---|
| `V1__extensions.sql` | `uuid-ossp` extension | All |
| `V2__calculator_runs.sql` | Partitioned `calculator_runs` table + partition function | Ingestion, Query |
| `V3__calculator_sli_daily.sql` | `calculator_sli_daily` aggregate table | DailyAggregationJob |
| `V4__sla_breach_events.sql` | `sla_breach_events` table | SLA evaluation |
| `V5__indexes.sql` | Consumer-facing composite indexes | `/batch/runs`, `/executions` |
| `V6__add_correlation_id.sql` | `correlation_id` column on `calculator_runs` | `/batch/runs` split-run grouping |

---

### Layer 1 — Domain Model
**`CalculatorRun`** (`domain/CalculatorRun.java`)
- Lombok `@Builder @Getter @Setter`
- Key fields: `runId`, `calculatorId`, `calculatorName`, `tenantId`, `frequency`, `reportingDate`, `startTime`, `endTime`, `durationMs`, `status`, `slaTime`, `expectedDurationMs`, `estimatedStartTime`, `estimatedEndTime`, `slaBreached`, `slaBreachReason`, `runNumber`, `runType`, `region`, `correlationId`, `isRerun` (transient), `createdAt`, `updatedAt`
- Helpers: `isDaily()`, `isMonthly()`, `isEndOfMonth()`

**Enums**: `RunStatus` (RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELLED + `isTerminal()`, `isSuccessful()`, `fromString()`), `CompletionStatus` (→ `toRunStatus()`), `Frequency` (`from()` lenient, `fromStrict()` strict)

---

### Layer 2 — Persistence
**`CalculatorRunRepository`** — core methods for Phase-1:

| Method | Purpose | Used by |
|---|---|---|
| `upsert(CalculatorRun)` | INSERT ON CONFLICT — idempotent write | Ingestion |
| `findById(String, LocalDate)` | Partition-pruned lookup by runId + date | `completeRun` |
| `findAllRunsByDateAndDimension(LocalDate, Frequency, String, List<String>)` | Batch fetch for `/batch/runs` | `CalculatorStateService` |
| `findRunsWithSlaStatusByName(String, Frequency, int, String)` | Raw runs LEFT JOIN breach events | `/executions` |
| `findLatestRunEstimatesByName(String, Frequency)` | Fallback estimates for NOT_STARTED entries | `CalculatorStateService` |
| `markSlaBreached(String, String, LocalDate)` | Flip breach flag on live detection (skip for Phase-1) | — |

Upsert immutability: ON CONFLICT UPDATE must **omit** `calculator_name`, `start_time`, `sla_time`, `expected_duration_ms`, `estimated_*`, `correlation_id`.

**`DailyAggregateRepository`** — for Phase-1:

| Method | Purpose |
|---|---|
| `recomputeForDateRange(LocalDate, LocalDate)` | Nightly rebuild of `calculator_sli_daily` |
| `findProfile(String, String, int)` | Frequency-scoped profile for SLA baseline |
| `findAllProfiles(String, int)` | Bulk for profile cache warm |

---

### Layer 3 — SLA Evaluation (on-write only)
**`SlaBaselineResolver`**
- `record SlaResolution(Long baselineDurationMs, Instant deadline)`
- `resolve(StartRunRequest, Frequency, CalculatorProfile) → SlaResolution`
  - Input chain: `slaTime` (ISO-8601 duration string, e.g. `PT2H30M`) → `expectedDurationMs` → `profile.avgDurationMs()` → null
  - Deadline formula: `startTime + round(baseline × (1 + thresholdPercent/100)) + lateBand`
  - Throws `DomainValidationException` for non-positive durations or non-duration ISO strings

**`SlaEvaluationService`**
- `evaluateSla(CalculatorRun) → SlaEvaluationResult`
  - FAILED/TIMEOUT → CRITICAL; no slaTime/durationMs → ON_TIME (ungraded)
  - `actual ≤ lateEdge` → ON_TIME; `≤ lateEdge + bandGap` → MEDIUM; beyond → HIGH

**`CalculatorProfileService`**
- `getProfile(String calculatorName, Frequency) → CalculatorProfile`
  - Cache-first (`obs:profile:{name}:{freq}`), DB fallback via `DailyAggregateRepository.findProfile()`
  - `warmProfiles(Frequency)` — called nightly by `DailyAggregationJob`

---

### Layer 4 — Ingestion
**`StartRunRequest`** / **`CompleteRunRequest`** DTOs

**`RunIngestionService`**
- `startRun(StartRunRequest, String tenantId) → CalculatorRun`
  1. Idempotent check: `findById(runId, reportingDate)` → return existing if present
  2. `CalculatorProfileService.getProfile(name, freq)`
  3. `SlaBaselineResolver.resolve()` → freeze `slaDeadline`, `baselineDurationMs`
  4. Resolve `estimatedStartTime`, `estimatedEndTime`
  5. `CalculatorRun.builder()...status(RUNNING)...slaTime(deadline)...build()`
  6. `runRepository.upsert(run)`
  7. Publish `RunStartedEvent` (skip `SlaMonitoringCache.register` for Phase-1)

- `completeRun(String runId, CompleteRunRequest, String tenantId) → CalculatorRun`
  1. `findById(runId, reportingDate)` → 404 if missing
  2. Tenant guard
  3. Guard: `status != RUNNING` → return early (duplicate complete)
  4. Validate `endTime >= startTime`
  5. Compute `durationMs`, set `endTime`, set `status`
  6. `SlaEvaluationService.evaluateSla(run)` → set `slaBreached`, `slaBreachReason`
  7. `runRepository.upsert(run)`
  8. Publish `RunCompletedEvent` or `SlaBreachedEvent`

**`RunIngestionController`** — `POST /api/v1/runs/start` (201 + Location), `POST /api/v1/runs/{runId}/complete` (200)

---

### Layer 5 — Cache (minimal for Phase-1)
**`AnalyticsCacheService`** — for `/executions` 5-min TTL
- `getFromCache(prefix, calculatorKey, frequency, days, runNumber, Class<T>)`
- `putInCache(prefix, calculatorKey, frequency, days, runNumber, Object)`
- Event listeners: `onRunStarted` (evict executions), `onRunCompleted`/`onSlaBreached` (evict all analytics keys for calculator)

**`CalculatorStateCacheService`** — for `/batch/runs`
- `getEntries(LocalDate, String frequency, String runNumber, List<String> names) → Map<String, CalculatorEntry>`
- `putEntries(LocalDate, String frequency, String runNumber, Map<String, CalculatorEntry> entries)`
- TTL: RUNNING → 30s; NOT_STARTED/empty → 60s; terminal with failure/breach → 5 min; terminal clean → 4 h

---

### Layer 6 — Query: `/batch/runs`
**`CalculatorStateService`**
- `getState(LocalDate, Frequency, String runNumber, List<String> names) → Map<String, CalculatorEntry>`
  1. Partial cache read via `CalculatorStateCacheService.getEntries()`
  2. DB fetch for cache misses via `findAllRunsByDateAndDimension()`
  3. Build entries:
     - Shared `correlationId` → `collapseSplitGroup()` → single `RunEntry` with worst-wins status
     - Sequential reruns (null correlationId, latest wins per `region:runType`) → `isRerun=true` when group > 1
     - No runs → `buildNotStartedEntry()`: profile (Redis) → `findLatestRunEstimatesByName()` → empty
  4. Write-back to cache

**`RunQueryController.getBatchRuns`** — `GET /api/v1/calculators/batch/runs`
- Params: `reporting_date` (LocalDate), `frequency`, `run_number` (optional), `keys` (pipe-separated names)
- Returns `CalculatorBatchRunsResponse`

**Response DTOs**: `CalculatorBatchRunsResponse` (record), `CalculatorEntry` (record), `RunEntry` (@Builder, @JsonInclude NON_NULL)

---

### Layer 7 — Query: `/executions`
**`AnalyticsService.getRunExecutionsByName`**
- `(String calculatorName, int days, Frequency, String runNumber) → RunPerformanceData`
  1. Normalize blank `runNumber` → null
  2. Cache check via `AnalyticsCacheService` (prefix `CACHE_EXECUTIONS`)
  3. On miss: `findRunsWithSlaStatusByName()` → `buildExecutionsResponse()` — each physical run = one `RunDataPoint`, no split-grouping
  4. Cache write

**`AnalyticsController.getRunExecutions`** — `GET /api/v1/analytics/calculators/{name}/executions`
- `@PathVariable("name") String calculatorName` ← explicit binding required (name ≠ parameter name)
- Params: `days` (default 30, 1–365), `frequency` (default DAILY), `run_number` (optional)
- Returns `RunPerformanceData`

**`RunPerformanceData`** (record) + **`RunDataPoint`** (record with `runId`, `reportingDate`, `startTime`, `endTime`, `durationMs`, `status`, `slaBreached`, `slaStatus`, `estimatedStartTime`, `slaTime`, `runNumber`, `expectedDurationMs`)

---

### Layer 8 — Nightly Aggregation
**`DailyAggregationJob`**
- Cron `0 30 0 * * *`, guard: `observability.aggregation.daily.enabled`
- `runDailyAggregation()`:
  1. `dailyAggregateRepository.recomputeForDateRange(today - recomputeWindowDays, today)` — delete+insert, idempotent
  2. `calculatorProfileService.warmProfiles(freq)` for each Frequency
  3. Expose Micrometer gauges: `recomputedRows`, `profilesWarmed`

---

## What to Skip (Phase-1 explicitly excluded)
- `SlaMonitoringCache` — Redis ZSET for live deadline tracking
- `LiveSlaBreachDetectionJob` — polling job for past-deadline runs
- `EarlyWarningJob` — pre-breach alerts
- `AlertHandlerService` — `SlaBreachedEvent` → `sla_breach_events` write + external alert
- `CacheWarmingService` — post-commit cache re-warm (can add without harm but not required)
- All analytics endpoints except `/executions`
- `RunQueryService` status/history (`/status`, `/batch/status`)
- `MaintenanceController` partition management

---

## Verification
```bash
# Start infra
docker compose up -d

# Run all unit tests (no Docker required for slice tests)
mvn test -Dtest="RunIngestionServiceTest,AnalyticsControllerTest,MaintenanceControllerTest,SlaMonitoringCacheTest"

# Full test suite (requires Docker for CalculatorRunRepositoryDimensionalTest)
SPRING_PROFILES_ACTIVE=local mvn clean test

# Manual smoke test
curl -X POST http://localhost:8080/api/v1/runs/start \
  -H "Content-Type: application/json" -u admin:admin \
  -d '{"runId":"r1","calculatorId":"c1","calculatorName":"calc-1","frequency":"DAILY","reportingDate":"2026-05-25","startTime":"2026-05-25T04:00:00Z"}'

curl http://localhost:8080/api/v1/calculators/batch/runs \
  -u admin:admin \
  -G --data-urlencode "reporting_date=2026-05-25" --data-urlencode "frequency=DAILY" --data-urlencode "keys=calc-1"

curl "http://localhost:8080/api/v1/analytics/calculators/calc-1/executions?days=7" -u admin:admin
```
