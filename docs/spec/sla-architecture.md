# SLA Architecture

SLA breach detection uses two independent mechanisms. Both ultimately publish `SlaBreachedEvent`, which triggers alert persistence and cache invalidation.

---

## Overview

| Mechanism | When it fires | What it catches |
|-----------|--------------|-----------------|
| **On-write evaluation** | During `completeRun()` | Runs that exceed SLA at the moment of completion reporting |
| **Live detection job** | Every 2 minutes | Runs that never call `complete` (hung, Airflow failure) |

Without the live detection job, a hung DAILY run that never calls `completeRun()` would have its SLA breach invisible to the system indefinitely.

---

## On-Write Evaluation (`SlaEvaluationService`)

Runs synchronously in the HTTP request thread during `completeRun()`.

### Checks Performed (in order, all evaluated)

| # | Condition | Breach Reason | Severity Logic |
|---|-----------|---------------|---------------|
| 1 | `endTime > slaTime` | `END_TIME_EXCEEDED` | Delay minutes: >60 → CRITICAL, >30 → HIGH, >15 → MEDIUM, ≤15 → LOW |
| 2 | `durationMs > expectedDurationMs × 1.5` | `DURATION_EXCEEDED` | Default MEDIUM |
| 3 | `status = FAILED or TIMEOUT` | `RUN_FAILED` | Always CRITICAL |

Check #4 (`STILL_RUNNING_PAST_SLA`) is only emitted by the live detection job.

**Multiple conditions:** All breach reasons are joined with `;`. Severity is the **highest** across all checks.

Example — a run that is late AND failed:
```
slaBreachReason: "END_TIME_EXCEEDED;RUN_FAILED"
severity: CRITICAL   (RUN_FAILED always wins)
```

### Start-Time Breach (during `startRun()`)

For **DAILY** runs only, a 5th breach check occurs at run start:

| Condition | Breach Reason |
|-----------|---------------|
| `startTime > slaDeadline` | `Start time exceeded SLA deadline` |

The run is created in `RUNNING` status and immediately marked `slaBreached=true`. The SLA deadline is `TimeUtils.calculateSlaDeadline(reportingDate, slaTimeCet)`.

---

## Live Detection Job (`LiveSlaBreachDetectionJob`)

Scheduled on the `scheduled-` thread pool.

### Detection Loop (every 120 seconds)

```
1. slaMonitoringCache.getBreachedRuns()
   → ZRANGEBYSCORE obs:sla:deadlines 0 <nowEpochMs>
   → Returns all run keys with SLA deadline ≤ now

2. For each breached run key:
   a. Parse {tenantId, runId, reportingDate} from key
   b. HGET obs:sla:run_info {runKey} → get cached metadata
   c. DB query: runRepository.findById(runId, reportingDate)

   Deduplication gates (any hit → deregister + skip):
   - DB returns empty           → deregister, skip
   - run.status != RUNNING      → deregister, skip (already completed)
   - run.slaBreached == true    → deregister, skip (already breached)

   If still RUNNING + not breached:
   d. Calculate delay = nowEpochMs - slaEpochMs (in minutes)
   e. runRepository.markSlaBreached(runId, "STILL_RUNNING_PAST_SLA;<delay>min", reportingDate)
   f. Build SlaEvaluationResult with delay-based severity
   g. eventPublisher.publishEvent(new SlaBreachedEvent(run, result))
   h. slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate)
   i. Increment counter: sla.breaches.live_detected (tagged by severity)

3. Record metrics:
   - sla.breach.live_detection.duration (execution time)
   - sla.breach.live_detection.count (breaches found this cycle)
   - sla.approaching.count gauge updated
```

### Early Warning Check (every 180 seconds)

```
slaMonitoringCache.getApproachingSlaRuns(10)
→ ZRANGEBYSCORE obs:sla:deadlines <now> <now + 600000ms>
→ Log WARN for each approaching run
→ Update gauge: sla.approaching.count
```

This is informational only — no breach event is fired for approaching runs.

---

## SLA Monitoring Registration

Only DAILY runs with a non-null `slaTime` and no prior breach are registered:

```java
// In RunIngestionService.startRun()
if (frequency == DAILY && run.getSlaTime() != null && !run.isSlaBreached()) {
    slaMonitoringCache.registerForSlaMonitoring(run);
}
```

MONTHLY runs are **never** registered. The live detection job has no frequency filter of its own — filtering is entirely upstream at registration time.

---

## Severity Scale

| Severity | Trigger Conditions |
|----------|--------------------|
| **LOW** | `END_TIME_EXCEEDED` with ≤ 15-minute delay |
| **MEDIUM** | `END_TIME_EXCEEDED` 15–30 min delay; `DURATION_EXCEEDED` (always) |
| **HIGH** | `END_TIME_EXCEEDED` 30–60 min delay |
| **CRITICAL** | `END_TIME_EXCEEDED` > 60 min delay; `RUN_FAILED` (always) |

When multiple conditions trigger, the **highest severity wins**.

---

## Alert Persistence Flow

`AlertHandlerService` listens to `SlaBreachedEvent` only:

```
SlaBreachedEvent received (async AFTER_COMMIT)
        │
        ▼
BUILD SlaBreachEvent record:
  - Determine BreachType from reason string
  - Determine Severity from result
  - Set alert_status = 'PENDING'
        │
        ▼
INSERT INTO sla_breach_events
  → DuplicateKeyException (run_id UNIQUE):
      log "Duplicate breach event for runId=..."
      increment sla.breaches.duplicate
      return
        │
        ▼
sendSimpleAlert() — currently logs WARN:
  "ALERT: SLA BREACH DETECTED for calculator=..."
        │
        ▼
UPDATE sla_breach_events:
  alerted = true
  alerted_at = NOW()
  alert_status = 'SENT'
```

!!! note "Alert delivery gap (TD-11)"
    `sendSimpleAlert()` currently only logs a warning message. No external notification channel (email, Slack, PagerDuty, webhook) is connected. The alert lifecycle columns (`alerted`, `alerted_at`, `alert_status`, `retry_count`, `last_error`) are fully wired for a real delivery implementation.

---

## Breach Type Enum

| `BreachType` | Description |
|-------------|-------------|
| `END_TIME_EXCEEDED` | Run completed after SLA deadline |
| `DURATION_EXCEEDED` | Run took > 150% of expected duration |
| `RUN_FAILED` | Run completed with FAILED or TIMEOUT status |
| `STILL_RUNNING_PAST_SLA` | Live detection: run still RUNNING after deadline |

---

## Alert Status Lifecycle

```
PENDING → SENT      (success)
PENDING → FAILED    (delivery error)
FAILED  → RETRYING  (queued for retry — currently not implemented)
RETRYING → ?        (see TD-4: RETRYING is not retried by findUnalertedBreaches)
```

!!! warning "TD-4: RETRYING status is stuck"
    `SlaBreachEventRepository.findUnalertedBreaches()` only fetches `alert_status IN ('PENDING', 'FAILED')`. A breach in `RETRYING` status will never be retried automatically. See [TD-4](tech-debt.md#td-4-retrying-alert-status-not-retried).

---

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `observability.sla.live-tracking.enabled` | `true` | Register DAILY runs in Redis SLA ZSET |
| `observability.sla.live-detection.enabled` | `true` | Enable `LiveSlaBreachDetectionJob` |
| `observability.sla.live-detection.interval-ms` | `120000` | Detection job interval (ms) |
| `observability.sla.live-detection.initial-delay-ms` | `30000` | Startup delay (ms) |
| `observability.sla.early-warning.enabled` | `true` | Enable early warning check |
| `observability.sla.early-warning.interval-ms` | `180000` | Early warning interval (ms) |
| `observability.sla.early-warning.threshold-minutes` | `10` | Warn when SLA within N minutes |
