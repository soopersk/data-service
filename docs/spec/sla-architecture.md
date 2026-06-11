# SLA Architecture

SLA breach detection uses two independent mechanisms. Both ultimately publish `SlaBreachedEvent`, which triggers alert persistence and cache invalidation.

---

## Duration-Based SLA Model

The SLA deadline is **derived from each calculator's own average runtime**, not from an absolute instant supplied upstream. At run start (`SlaBaselineResolver`) a baseline duration is resolved and frozen into the run's `slaTime`:

```
baseline   = best available duration estimate (fallback chain below)
buffered    = baseline × (1 + thresholdPercent)         # default 20%
slaTime     = startTime + buffered + lateBand            # the ON_TIME upper edge (entry into LATE)
```

Baseline fallback chain (best first):

1. **Average** — the cached `CalculatorProfile`'s avg duration (per `calculatorId/tenantId/frequency`), gated by `min-sample-size`.
2. **`expectedDurationMs`** — request-supplied duration.
3. **`slaTime` budget** — `request.slaTime − startTime`, if positive (last resort; `slaTime` is now an **optional** request field).
4. **None** — no deadline; the run is left **ungraded** (treated ON_TIME) until history accrues.

Grading at completion uses the frozen `slaTime` plus the band gap:

| Actual duration vs frozen edges | Status | Breach | Severity |
|---|---|---|---|
| `≤ slaTime − startTime` (= buffered + lateBand) | ON_TIME | no | — |
| `≤ buffered + veryLateBand` | LATE | yes | MEDIUM |
| `> buffered + veryLateBand` | VERY_LATE | yes | HIGH |
| `FAILED` / `TIMEOUT` | — | yes | CRITICAL |

Defaults: `thresholdPercent=20`, `lateBand=15m`, `veryLateBand=30m`, `min-sample-size=5`. The baseline is resolved from a Redis-cached profile warmed nightly (see [Data Architecture](data-architecture.md)); the profile read does not hit the DB on a warm cache.

---

## Overview

| Mechanism | When it fires | What it catches |
|-----------|--------------|-----------------|
| **On-write evaluation** | During `completeRun()` | Runs whose actual duration lands in the LATE/VERY_LATE band, or that FAILED/TIMED OUT |
| **Live detection job** | Every 2 minutes | Runs that never call `complete` (hung, Airflow failure) — still RUNNING past the frozen deadline |

Without the live detection job, a hung run that never calls `completeRun()` would have its SLA breach invisible to the system indefinitely. Both DAILY and MONTHLY runs are monitored.

---

## On-Write Evaluation (`SlaEvaluationService`)

Runs synchronously in the HTTP request thread during `completeRun()`. Classifies the run's **actual duration** against the frozen `slaTime` edges (see the Duration-Based SLA Model above).

### Classification (single decision)

| Order | Condition | Result | Severity | Breach reason |
|---|---|---|---|---|
| 1 | `status = FAILED or TIMEOUT` | breach | CRITICAL | `Run status: <status>` |
| 2 | `durationMs ≤ slaTime − startTime` | ON_TIME | — | (none) |
| 3 | `durationMs ≤ (slaTime − startTime) + bandGap` | LATE | MEDIUM | `Finished N minutes late (LATE band)` |
| 4 | otherwise | VERY_LATE | HIGH | `Finished N minutes late (VERY_LATE band)` |

`bandGap = veryLateBand − lateBand` (default 15m). When `slaTime` is `null` (ungraded run), the result is ON_TIME / no breach. The old absolute-time check and the `durationMs > expected × 1.5` check were **removed** — duration is graded once, against the derived deadline.

### Deadline derivation at start (`startRun()`)

There is **no start-time breach** in the duration model ("started late" is not a concept). At start, `SlaBaselineResolver` resolves the deadline (fallback chain above) and freezes it into `slaTime`. The run is created `RUNNING` with `slaBreached=false`.

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
   d. Calculate delay = nowEpochMs - slaEpochMs (the frozen LATE edge)
   e. runRepository.markSlaBreached(runId, "Still running N minutes past SLA deadline", reportingDate)
   f. Build SlaEvaluationResult: within one bandGap past the edge → MEDIUM, beyond → HIGH
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

Any run (DAILY **or** MONTHLY) gets registered when a deadline was derived and live tracking is enabled:

```java
// In RunIngestionService.startRun()
if (liveTrackingEnabled && slaDeadline != null) {
    slaMonitoringCache.registerForSlaMonitoring(run);
}
```

A run with no resolvable baseline (`slaDeadline == null`) is not registered — it is ungraded until history accrues. MONTHLY monitoring is new behavior; monthly samples are sparse, so the baseline often falls back to `expectedDurationMs` (then `slaTime`) until ~13 months of history exist.

---

## Severity Scale

| Severity | Trigger Conditions |
|----------|--------------------|
| **MEDIUM** | LATE band — actual duration past the ON_TIME edge but within one band gap |
| **HIGH** | VERY_LATE band — actual duration beyond the band gap |
| **CRITICAL** | `FAILED` or `TIMEOUT` (always) |

(`LOW` is no longer produced by duration grading.) For live detection, severity is graded by minutes past the frozen deadline using the same band gap.

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

`AlertHandlerService` derives the persisted `breach_type` from the breach-reason string. Under the duration model the reasons are now band-based — `Run status: <status>` (FAILED/TIMEOUT), `Finished N minutes late (LATE|VERY_LATE band)` (on-write), and `Still running N minutes past SLA deadline` (live detection). The `BreachType` enum values themselves are unchanged:

| `BreachType` | Meaning under the duration model |
|-------------|-------------|
| `END_TIME_EXCEEDED` | Completed in the LATE/VERY_LATE band (actual duration past the derived deadline) |
| `DURATION_EXCEEDED` | (Legacy) — the explicit 150%-of-expected check was removed; duration is graded via the bands |
| `RUN_FAILED` | Run completed with FAILED or TIMEOUT status |
| `STILL_RUNNING_PAST_SLA` | Live detection: run still RUNNING after the derived deadline |

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
| `observability.sla.duration-based.enabled` | `true` | DURATION mode only: derive the deadline from avg runtime; `false` = pass request `slaTime` through unchanged |
| `observability.sla.duration-based.threshold-percent` | `20` | DURATION mode only: percentage buffer over the baseline |
| `observability.sla.late-band-minutes` | `15` | Shared (both modes): ON_TIME upper edge beyond the frozen `slaTime`; also baked into the DURATION deadline |
| `observability.sla.very-late-band-minutes` | `30` | Shared (both modes): LATE upper edge beyond the frozen `slaTime` |
| `observability.sla.min-sample-size` | `5` | Shared (both modes): runs required before the historical average is trusted |
| `observability.sla.lookback.daily-days` | `30` | Shared (both modes): trailing window for DAILY baselines |
| `observability.sla.lookback.monthly-days` | `395` | Shared (both modes): trailing window for MONTHLY baselines |
| `observability.sla.live-tracking.enabled` | `true` | Register runs (DAILY + MONTHLY) in the Redis SLA ZSET |
| `observability.sla.live-detection.enabled` | `true` | Enable `LiveSlaBreachDetectionJob` |
| `observability.sla.live-detection.interval-ms` | `120000` | Detection job interval (ms) |
| `observability.sla.live-detection.initial-delay-ms` | `30000` | Startup delay (ms) |
| `observability.sla.early-warning.enabled` | `true` | Enable early warning check |
| `observability.sla.early-warning.interval-ms` | `180000` | Early warning interval (ms) |
| `observability.sla.early-warning.threshold-minutes` | `10` | Warn when SLA within N minutes |
