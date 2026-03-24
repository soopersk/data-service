# Failure Modes & Recovery

This section documents expected system behaviour under various failure conditions and how recovery occurs.

---

## Redis Outage

| Scenario | Behaviour | Recovery |
|----------|-----------|----------|
| Redis unreachable on read | Returns cache miss; falls through to PostgreSQL | Automatic — self-healing on reconnect |
| Redis unreachable on write | Logs error; DB write already committed; cache miss on next read | Automatic — cache rebuilt on first read-through |
| SLA monitoring ZSET unavailable | Live breach detection fails silently; `sla.breach.live_detection.failures` counter incremented; no live-path breaches until Redis recovers | Automatic on reconnect; missed breaches during outage will not be retroactively detected |
| Cache warming after completion | Warm operation fails; data in DB; cache stale until TTL or next write | Automatic — stale cache expires; next read repopulates |
| Analytics cache unavailable | All analytics requests hit DB; 5-min cache bypassed | Automatic |

**Self-healing mechanism:** Lettuce (the Redis client) automatically reconnects after connection loss. Once Redis is available, the next write operation populates the cache; the next read-through on a miss also repopulates it.

!!! warning "Live SLA detection blind spot"
    During a Redis outage, `LiveSlaBreachDetectionJob` cannot query `obs:sla:deadlines`. Runs that breach their SLA during the outage will only be detected when `completeRun()` is eventually called (on-write path). If a run never calls `completeRun()` (hung), its breach will be permanently missed for the duration of the outage.

---

## Database Outage

| Scenario | Behaviour |
|----------|-----------|
| DB unreachable on read | Cached data continues to serve up to TTL (30s–60s for status, 5min for analytics) |
| DB unreachable on write | `POST /runs/start` or `POST /runs/complete` fail with `500`; HikariCP connection timeout = 30s |
| Status queries — cached | Serve stale data until cache TTL expires |
| Status queries — cache miss | Fail with `500` after HikariCP timeout |
| SLA detection | `findById()` fails per-run; exception caught; loop continues for other runs |
| Partition creation job | Fails with logged error; retries next night at 01:00 |
| Daily aggregate update | Fails silently; analytics data will be stale until DB recovers and run completes |

**Recovery:** Requires DB connectivity restoration. No write buffering or queue exists — in-flight requests during an outage are lost.

---

## Partial Write Failures

| Failure Point | Consequence | Data Integrity |
|---------------|------------|----------------|
| DB upsert succeeds, Redis write fails | Cache miss on next read; DB queried; eventual consistency | DB is authoritative — no data loss |
| DB upsert succeeds, SLA registration fails | Run is not monitored for live SLA breach; on-write evaluation still applies | No data loss; just reduced live detection coverage |
| DB upsert succeeds, event publish fails | No cache warming; no alert record | Data in DB; cache stale until TTL or next write |
| Alert INSERT fails (`DuplicateKeyException`) | Caught, logged, counter incremented | Original run commit unaffected |
| Alert INSERT fails (other error) | Logged; `alert_status` stays `PENDING` or `FAILED` | Breach record exists in DB |
| Alert send fails (after INSERT) | `alertStatus = 'FAILED'`, `retry_count` incremented | Breach persisted; delivery pending retry |

---

## SLA Detection Job — Crash & Error Handling

The `LiveSlaBreachDetectionJob` has two levels of exception handling:

```
Outer try-catch (per execution):
  Any uncaught exception → log error → job retries on next interval (120s)

Inner try-catch (per run in the detection loop):
  Any exception for a single run → increment sla.breach.live_detection.failures
  → continue processing remaining runs in this cycle
```

A single run failure does not abort the detection cycle. A total job failure auto-retries in 120 seconds.

---

## Partition Creation Failure

**Trigger:** `PartitionManagementJob.createPartitions()` fails (DB connection issue, permission error, etc.)

**Consequence:**
- `partitions.create.failures` counter incremented
- No partitions created for that night
- If failure persists and the **current day's partition** is missing: all `INSERT` statements for that date fail with `ERROR: no partition of relation found for row`

**Mitigation:**
- The job creates a **60-day forward window** — a single missed night is extremely unlikely to result in a missing partition (59 days of buffer remain)
- The job is idempotent (`CREATE PARTITION IF NOT EXISTS`) — manual re-run or the next night's execution recovers

**Recommended alert:** Configure an alert on `partitions.create.failures > 0` to catch failures early.

---

## Concurrent Write Behaviour

### Duplicate Start Events

Airflow may retry a DAG task on failure, causing duplicate `POST /runs/start` calls:

- The `ON CONFLICT (run_id, reporting_date) DO UPDATE` upsert is idempotent
- The second call returns the existing run unchanged
- `calculator.runs.start.duplicate` counter incremented
- No duplicate records created

### Duplicate Complete Events

Same protection applies to `POST /runs/complete`:

- If run is not in `RUNNING` status, it is returned unchanged
- `calculator.runs.complete.duplicate` counter incremented

### Concurrent SLA Registrations

`SlaMonitoringCache.registerForSlaMonitoring()` uses `ZADD` — concurrent calls from multiple threads for the same run key result in the last write winning. The score (SLA deadline epoch ms) is deterministic, so concurrent registrations are safe.

---

## Analytics Under Heavy Load

All analytics endpoints have a 5-minute Redis cache. Cache miss behaviour:

| Endpoint | Miss Behaviour | Risk |
|----------|---------------|------|
| `/runtime`, `/sla-summary`, `/trends` | Query `calculator_sli_daily` (unpartitioned, small) | Low |
| `/performance-card` (days ≤ 90) | Query `calculator_runs` (partitioned, bounded) | Low |
| `/performance-card` (days = 365, concurrent) | Multiple parallel queries to 365 partitions + JOIN | Medium |
| `/sla-breaches` (no cache) | Always hits DB; cursor pagination is O(1) | Low with cursor, high with large offsets |

---

## Startup Behaviour

| Phase | Behaviour |
|-------|-----------|
| Spring context initialization | Flyway migrations run synchronously before the HTTP server starts |
| First request | All cache keys are cold (miss) — DB queries for first access |
| SLA detection job | Starts after 30-second initial delay to allow cache warm-up |
| Partition management job | Runs at 01:00 only — no startup action |

If Flyway migration fails on startup, the application does not start. All pending migrations must succeed before HTTP traffic is accepted.
