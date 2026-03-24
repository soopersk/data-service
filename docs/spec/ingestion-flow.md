# Ingestion & Event Flow

This section documents the step-by-step execution of the two primary write operations: `startRun()` and `completeRun()`.

---

## `startRun()` — Step-by-Step

Called by `RunIngestionService.startRun()` from `POST /api/v1/runs/start`.

```
1. Idempotency check: findById(runId, reportingDate)
   → EXISTS: return existing run, increment calculator.runs.start.duplicate counter
   → DOES NOT EXIST: continue

2. MONTHLY validation:
   → Warn (non-blocking) if reportingDate is not the last day of the month

3. Compute SLA deadline (DAILY only):
   slaDeadline = TimeUtils.calculateSlaDeadline(reportingDate, slaTimeCet)
   → CET time-of-day + reporting date + DST offset = UTC TIMESTAMPTZ

4. Start-time breach check (DAILY only):
   IF startTime > slaDeadline:
     run.slaBreached = true
     run.slaBreachReason = "Start time exceeded SLA deadline"

5. Build CalculatorRun domain object (status = RUNNING)

6. DB write: runRepository.upsert(run)
   → INSERT ... ON CONFLICT (run_id, reporting_date) DO UPDATE
   → Only mutable fields updated on conflict (start/sla/name are immutable)

7. SLA monitoring registration:
   Condition: frequency == DAILY AND slaTime != null AND not breached
   slaMonitoringCache.registerForSlaMonitoring(run)
   → ZADD obs:sla:deadlines <slaEpochMs> {tenantId}:{runId}:{reportingDate}
   → HSET obs:sla:run_info {runKey} {json metadata}
   (both keys: 24h TTL)

8. If breached at start → publish SlaBreachedEvent(run, result)
   (async AFTER_COMMIT — steps 8a–8d run after the DB transaction commits)
   8a. AlertHandlerService: build SlaBreachEvent record
   8b. INSERT into sla_breach_events (DuplicateKeyException → logged, skipped)
   8c. Log WARN alert message
   8d. UPDATE sla_breach_events: alerted=true, alerted_at=now, alert_status='SENT'
   8e. AnalyticsCacheService: invalidate obs:analytics:* keys for this calculator

9. publish RunStartedEvent(run)
   (async AFTER_COMMIT)
   9a. CacheWarmingService.onRunStarted():
       - evictStatusResponse() → DEL obs:status:hash:{calcId}:{tenantId}:{freq}
       - evictRecentRuns() → DEL obs:runs:zset:{calcId}:{tenantId}:{freq}
       - warmCacheForRun() → findRecentRuns(limit=20) → re-populate Redis

10. Return CalculatorRun → controller converts to RunResponse → 201 Created
```

---

## `completeRun()` — Step-by-Step

Called by `RunIngestionService.completeRun()` from `POST /api/v1/runs/{runId}/complete`.

```
1. Run lookup: findRecentRun(runId)
   a. findRecentRunsForCompletion(runId) — searches last 7 days of partitions
   b. FALLBACK (only if not found): findById(runId) — full partition scan ⚠️ TD-1

2. Tenant validation:
   run.tenantId MUST match caller's X-Tenant-Id
   → mismatch → DomainAccessDeniedException → 403 Forbidden

3. Idempotency check: status != RUNNING
   → return existing run unchanged, increment calculator.runs.complete.duplicate counter

4. Validation:
   request.endTime MUST be after run.startTime
   → violation → DomainValidationException → 400 Bad Request

5. Compute duration:
   durationMs = ChronoUnit.MILLIS.between(run.startTime, request.endTime)

6. Resolve completion status:
   RunStatus.fromCompletionStatus(request.status) → defaults to SUCCESS if null/blank

7. SLA evaluation: SlaEvaluationService.evaluateSla(run, endTime, status)
   Checks (in order, ALL conditions evaluated):
   a. endTime > slaTime             → END_TIME_EXCEEDED    (delay-based severity)
   b. durationMs > expected * 1.5  → DURATION_EXCEEDED     (MEDIUM)
   c. status = FAILED or TIMEOUT    → RUN_FAILED           (CRITICAL)
   Returns SlaEvaluationResult(breached, combinedReason, highestSeverity)

8. Apply SLA result to run object:
   run.slaBreached = result.breached
   run.slaBreachReason = result.reason (multiple reasons joined with ";")
   run.endTime = endTime
   run.durationMs = durationMs
   run.endHourCet = TimeUtils.toDecimalCetHour(endTime)

9. DB write: runRepository.upsert(run)
   → INSERT ... ON CONFLICT (run_id, reporting_date) DO UPDATE
   → Updates: end_time, duration_ms, end_hour_cet, status, sla_breached, sla_breach_reason, updated_at

10. Deregister from SLA monitoring:
    slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate)
    → ZREM obs:sla:deadlines {runKey}
    → HDEL obs:sla:run_info {runKey}

11. Update daily aggregate:
    dailyAggregateRepository.upsertDaily(run)
    → Increments total_runs, success_runs (if SUCCESS), sla_breaches (if breached)
    → Recalculates running averages: avg_duration_ms, avg_start_min_cet, avg_end_min_cet

12. Publish event (async AFTER_COMMIT):
    IF sla_breached:
      → SlaBreachedEvent → AlertHandlerService + CacheWarmingService + AnalyticsCacheService
    ELSE:
      → RunCompletedEvent → CacheWarmingService + AnalyticsCacheService

    CacheWarmingService (either event):
      a. evictCacheForRun(): evict status hash + evict runs ZSET
      b. warmCacheForRun(): findRecentRuns(limit=20) → re-populate Redis

    AnalyticsCacheService:
      → Bulk delete all obs:analytics:* keys for this calculator

13. Return CalculatorRun → controller converts to RunResponse → 200 OK
```

---

## Sequence Diagram

```mermaid
sequenceDiagram
    participant Airflow
    participant RunIngestionService
    participant SlaEvaluationService
    participant Repositories
    participant Redis
    participant AlertHandler

    %% --- START FLOW ---
    Airflow->>RunIngestionService: POST /start
    RunIngestionService->>Repositories: findById(runId, reportingDate)
    alt Duplicate run
        Repositories-->>RunIngestionService: existing run
        RunIngestionService-->>Airflow: 200 OK (existing)
    else New run
        RunIngestionService->>Repositories: upsert(run)
        RunIngestionService->>Redis: ZADD sla:deadlines
        alt Start-time breach
            RunIngestionService-->>AlertHandler: publish SlaBreachedEvent (async)
        end
        RunIngestionService-->>AlertHandler: publish RunStartedEvent (async)
        AlertHandler->>Redis: evict + warm cache
        RunIngestionService-->>Airflow: 201 Created
    end

    %% --- COMPLETE FLOW ---
    Airflow->>RunIngestionService: POST /complete
    RunIngestionService->>Repositories: findRecentRun(runId)
    RunIngestionService->>SlaEvaluationService: evaluateSla()
    SlaEvaluationService-->>RunIngestionService: SlaEvaluationResult
    RunIngestionService->>Repositories: upsert(run)
    RunIngestionService->>Redis: ZREM sla:deadlines
    RunIngestionService->>Repositories: upsertDaily()
    alt SLA Breached
        RunIngestionService-->>AlertHandler: publish SlaBreachedEvent (async)
        AlertHandler->>Repositories: INSERT sla_breach_events
        AlertHandler->>Redis: evict + warm cache
    else Not Breached
        RunIngestionService-->>AlertHandler: publish RunCompletedEvent (async)
        AlertHandler->>Redis: evict + warm cache
    end
    RunIngestionService-->>Airflow: 200 OK
```

---

## Event Listener Guarantees

All event listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`. This provides two critical guarantees:

1. **Durability before effect**: Cache warming and alert creation only happen after the DB transaction is committed. If the DB write fails, no stale cache or phantom alerts are created.

2. **Non-blocking HTTP thread**: Listeners execute on the `async-` thread pool, not the HTTP request thread. The caller receives their response immediately after DB commit without waiting for cache operations.

### What Happens if an Async Listener Fails?

| Failure | Consequence | Recovery |
|---------|-------------|----------|
| `CacheWarmingService` fails | Cache not refreshed; next query re-reads from DB | Automatic on next read |
| `AlertHandlerService` INSERT fails | `DuplicateKeyException` → logged, skipped. Other errors → logged | Manual or retry sweep |
| `AnalyticsCacheService` invalidation fails | Stale analytics cache until 5-min TTL expires | Automatic TTL expiry |

---

## Idempotency Matrix

| Operation | Duplicate Condition | Response | Side Effect |
|-----------|--------------------|-----------|-|
| `startRun()` | Same `(runId, reportingDate)` exists | Return existing run | `calculator.runs.start.duplicate` incremented |
| `completeRun()` | Run status is not `RUNNING` | Return existing run | `calculator.runs.complete.duplicate` incremented |
| `AlertHandlerService` INSERT | `run_id` already in `sla_breach_events` | `DuplicateKeyException` caught | `sla.breaches.duplicate` incremented |
