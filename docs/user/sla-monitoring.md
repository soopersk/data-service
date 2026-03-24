# SLA Monitoring

The Observability Service provides two independent SLA breach detection mechanisms: synchronous on-write evaluation and asynchronous live detection.

---

## How SLA Time Works

When you call `POST /runs/start`, you provide a `slaTimeCet` field — the SLA deadline expressed as a time-of-day in CET (Central European Time):

```json
{
  "slaTimeCet": "06:30:00",
  "reportingDate": "2026-03-24"
}
```

The service computes the UTC SLA deadline by combining the reporting date and the CET time, accounting for DST. This deadline is stored in the `sla_time` column and used for all subsequent breach evaluation.

!!! info "DAILY only"
    Live SLA monitoring applies to **DAILY** runs only. MONTHLY runs are evaluated on-write when `complete` is called, but are not registered in the Redis SLA monitoring set for live detection.

---

## Breach Detection Mechanisms

### 1. On-Write Evaluation

Runs synchronously during `POST /runs/start` and `POST /runs/{runId}/complete`.

**During `start`:**

| Condition | Breach Reason | When it triggers |
|-----------|---------------|-----------------|
| `startTime > slaDeadline` | Start time exceeded SLA deadline | Run started so late it cannot finish by SLA |

**During `complete`:**

| Condition | Breach Reason | Severity |
|-----------|---------------|----------|
| `endTime > slaTime` | `END_TIME_EXCEEDED` | Scaled by delay minutes |
| `durationMs > expectedDurationMs × 1.5` | `DURATION_EXCEEDED` | MEDIUM |
| `status = FAILED` or `TIMEOUT` | `RUN_FAILED` | CRITICAL |

Multiple conditions can trigger simultaneously. All breach reasons are concatenated with `;` and the highest severity is used.

### 2. Live Detection Job

The `LiveSlaBreachDetectionJob` runs every **2 minutes** and detects runs that have **never called `complete`** — hung runs, Airflow failures, or infrastructure issues.

**How it works:**

1. Queries Redis for runs whose SLA deadline has passed: `ZRANGEBYSCORE obs:sla:deadlines 0 <now>`
2. For each overdue run, verifies it is still `RUNNING` in the database
3. If still running and not already marked as breached, marks `sla_breached=true` and fires an `SlaBreachedEvent`
4. Deregisters the run from Redis monitoring

**Early Warning (every 3 minutes):**

A separate check identifies runs approaching their SLA within the next 10 minutes. These generate a log warning and increment the `sla.approaching.count` gauge — no breach is recorded yet.

---

## Severity Levels

| Severity | `END_TIME_EXCEEDED` / `STILL_RUNNING_PAST_SLA` | `RUN_FAILED` | `DURATION_EXCEEDED` |
|----------|------------------------------------------------|--------------|---------------------|
| **LOW** | Delay ≤ 15 minutes | — | — |
| **MEDIUM** | Delay 15–30 minutes | — | Always MEDIUM |
| **HIGH** | Delay 30–60 minutes | — | — |
| **CRITICAL** | Delay > 60 minutes | Always CRITICAL | — |

When multiple breach conditions trigger, the **highest severity wins**.

---

## SLA Status in Analytics

### Traffic Light (Summary Endpoints)

The `sla-summary` and `trends` endpoints classify each day using a traffic light:

| Colour | Condition |
|--------|-----------|
| 🟢 **GREEN** | No SLA breaches on that day |
| 🟡 **AMBER** | Breach(es) exist, none with severity HIGH or CRITICAL |
| 🔴 **RED** | At least one breach with severity HIGH or CRITICAL |

### Per-Run Status (Performance Card)

| Status | Meaning |
|--------|---------|
| `SLA_MET` | Run completed within the SLA deadline |
| `LATE` | Breach with severity LOW or MEDIUM |
| `VERY_LATE` | Breach with severity HIGH or CRITICAL |

---

## Breach Event Lifecycle

When a breach is detected, the service creates a record in the `sla_breach_events` table:

```
NEW BREACH DETECTED
        │
        ▼
sla_breach_events INSERT
  alert_status = 'PENDING'
  alerted = false
        │
        ▼
AlertHandlerService.sendSimpleAlert()
  (currently: log warning)
        │
        ▼
  alert_status = 'SENT'
  alerted = true
  alerted_at = <now>
```

**Alert Status Values:**

| Status | Meaning |
|--------|---------|
| `PENDING` | Breach recorded, alert not yet sent |
| `SENT` | Alert successfully delivered |
| `FAILED` | Alert delivery failed; `retry_count` incremented |
| `RETRYING` | Breach is queued for retry |

!!! note "Alert delivery"
    The current implementation logs the breach as a `WARN`-level message. No external notification channel (email, Slack, PagerDuty) is wired. The alert infrastructure (PENDING → SENT lifecycle, retry count) is fully built and ready for integration.

---

## Idempotency

The `sla_breach_events` table has a `UNIQUE` constraint on `run_id`. If a breach event is detected multiple times (e.g., by both the on-write path and the live detection job), the second insert is silently ignored. Each run will have at most one breach record.

---

## Querying Breach Events

Use the [Analytics API — SLA Breaches](analytics-api.md#get-apiv1analyticscalculatorscalculatoridsla-breaches) endpoint to retrieve paginated breach records:

```bash
# All breaches for a calculator in the last 30 days
curl -u admin:admin -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/sla-breaches?days=30"

# Only CRITICAL breaches
curl -u admin:admin -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/sla-breaches?days=30&severity=CRITICAL"
```

---

## SLA Monitoring Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `observability.sla.live-tracking.enabled` | `true` | Register DAILY runs in Redis SLA monitoring |
| `observability.sla.live-detection.enabled` | `true` | Enable the live detection job |
| `observability.sla.live-detection.interval-ms` | `120000` (2 min) | How often the detection job runs |
| `observability.sla.early-warning.enabled` | `true` | Enable the early warning check |
| `observability.sla.early-warning.threshold-minutes` | `10` | Warn if SLA within this many minutes |

To disable live SLA monitoring entirely:

```yaml
observability:
  sla:
    live-tracking:
      enabled: false
    live-detection:
      enabled: false
```
