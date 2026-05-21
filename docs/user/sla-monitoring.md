# SLA Monitoring

The Observability Service provides two independent SLA breach detection mechanisms: synchronous on-write evaluation and asynchronous live detection.

---

## How SLA Time Works (duration-based)

The SLA deadline is **derived from each calculator's own average runtime**, not from an absolute time you supply. At `POST /runs/start` the service resolves a baseline duration and freezes a deadline into the `sla_time` column:

```
baseline  = avg runtime (cached profile)  ▸ else expectedDurationMs  ▸ else (slaTime − startTime)
slaTime   = startTime + baseline × (1 + thresholdPercent) + lateBand
```

- `slaTime` (in the start request) is now **optional** — it is only used as a last-resort baseline if there is no history and no `expectedDurationMs`.
- `expectedDurationMs` (optional) is a good per-run baseline when history is thin.
- If none of these are available, the run is left **ungraded** (treated ON_TIME) until enough history accrues.

A run is graded at completion by comparing its **actual duration** to the frozen deadline plus grace bands (defaults: 20% buffer, 15-minute LATE band, 30-minute VERY_LATE band).

!!! info "DAILY and MONTHLY"
    Both DAILY and MONTHLY runs are graded and registered for live monitoring (whenever a deadline was derived). MONTHLY history is sparse, so monthly baselines often fall back to `expectedDurationMs` until ~13 months of history exist.

---

## Breach Detection Mechanisms

### 1. On-Write Evaluation

Runs synchronously during `POST /runs/{runId}/complete`. (There is **no start-time breach** — "started late" is not a concept in the duration model. The deadline is simply derived and frozen at start.)

**During `complete`** — the actual duration is classified once against the frozen deadline:

| Condition | Status | Severity |
|-----------|--------|----------|
| `status = FAILED` or `TIMEOUT` | breach | CRITICAL |
| `duration ≤ buffered + lateBand` | ON_TIME | — |
| `duration ≤ buffered + veryLateBand` | LATE | MEDIUM |
| `duration > buffered + veryLateBand` | VERY_LATE | HIGH |

(`buffered = baseline × (1 + thresholdPercent)`; the deadline frozen into `slaTime` is `startTime + buffered + lateBand`.)

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

| Severity | Trigger |
|----------|---------|
| **MEDIUM** | LATE band — duration past the ON_TIME edge but within one band gap (`veryLateBand − lateBand`) |
| **HIGH** | VERY_LATE band — duration beyond the band gap |
| **CRITICAL** | `FAILED` or `TIMEOUT` (always) |

Live detection grades a still-running breach the same way: within one band gap past the deadline → MEDIUM, beyond → HIGH.

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
| `ON_TIME` | Duration within the ON_TIME edge (no breach) |
| `LATE` | LATE band — breach with severity MEDIUM |
| `VERY_LATE` | VERY_LATE band — breach with severity HIGH (or CRITICAL for FAILED/TIMEOUT) |

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
| `observability.sla.duration-based.enabled` | `true` | Derive the deadline from avg runtime (`false` = use request `slaTime` as-is) |
| `observability.sla.duration-based.threshold-percent` | `20` | Buffer over the baseline |
| `observability.sla.duration-based.late-band-minutes` | `15` | ON_TIME upper edge |
| `observability.sla.duration-based.very-late-band-minutes` | `30` | LATE upper edge |
| `observability.sla.duration-based.min-sample-size` | `5` | Runs before the average is trusted |
| `observability.sla.live-tracking.enabled` | `true` | Register runs (DAILY + MONTHLY) in Redis SLA monitoring |
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
