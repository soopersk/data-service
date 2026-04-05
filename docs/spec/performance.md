# Performance Model

---

## Threading Model

| Thread Pool | Configuration | Used by |
|-------------|--------------|---------|
| HTTP (Tomcat) | Default — 200 max threads | All HTTP request processing |
| Async executor | 5 core / 10 max / 100 queue capacity, prefix `async-` | `@Async` event listeners: `AlertHandlerService`, `CacheWarmingService`, `AnalyticsCacheService` |
| Scheduling pool | 5 threads, prefix `scheduled-` | `@Scheduled` jobs: `LiveSlaBreachDetectionJob`, `PartitionManagementJob` |

### Async Executor Bottleneck

Under spike load with 10+ concurrent `completeRun()` requests:

1. Each request publishes `RunCompletedEvent` or `SlaBreachedEvent`
2. Each event dispatches to `CacheWarmingService.warmCacheForRun()` on the async pool
3. Each warm operation calls `findRecentRuns(limit=20)` — a fast DB query but it holds a HikariCP connection

With 10 max async threads and a queue of 100, there is potential for queueing under heavy concurrent completion load. Each queued event holds memory but does not block the HTTP thread.

---

## DB Connection Pool (HikariCP)

| Parameter | Value |
|-----------|-------|
| Maximum pool size | 20 |
| Minimum idle | 5 |
| Connection timeout | 30s |
| Idle timeout | 10min |
| Max lifetime | 30min |
| Leak detection threshold | 60s |
| Pool name | `ObservabilityHikariCP` |

HikariCP is the fastest available JDBC connection pool. The 20-connection ceiling is shared across:
- HTTP request threads (synchronous DB writes)
- Async event threads (cache warming DB reads)
- Scheduled job threads (SLA detection DB queries)

---

## Latency Budget per Endpoint

| Endpoint | Cached Path | Uncached Path | Notes |
|----------|-------------|---------------|-------|
| `POST /runs/start` | N/A | **10–30ms** | DB upsert + SLA register + event publish |
| `POST /runs/{runId}/complete` | N/A | **15–50ms** | DB upsert + SLA eval + aggregate upsert + event |
| `GET /calculators/{id}/status` (DAILY, cached) | **~0.5ms** | **5–15ms** | Redis hash get vs 4-partition SQL |
| `GET /calculators/{id}/status` (MONTHLY, cached) | **~0.5ms** | **50–200ms** | ~395 partitions scanned on miss |
| `POST /calculators/batch/status` (20 calcs, cached) | **~2ms** | **10–40ms** | Pipeline Redis vs window function query |
| `GET .../runtime` (cached) | **~0.5ms** | **5–20ms** | Pre-aggregated table |
| `GET .../runtime` (uncached, 365 days) | **~0.5ms** | **30–100ms** | 365 rows from `calculator_sli_daily` |
| `GET .../sla-breaches` (cursor, size=20) | N/A (no cache) | **5–15ms** | Keyset index scan |
| `GET .../sla-breaches` (offset page=100) | N/A | **100–500ms+** | Full index scan to offset position |
| `GET .../run-performance` (cached) | **~0.5ms** | **50–300ms** | `calculator_runs` LEFT JOIN `sla_breach_events` |

---

## Redis Interaction Cost

| Operation | Redis Commands | Estimated Latency |
|-----------|---------------|-------------------|
| Status cache read (hit) | `HGET` | ~0.5ms |
| Status cache write | `HSET` + `EXPIRE` | ~1ms |
| Batch status (20 calcs, cached) | Pipeline of 20 `HGET` | ~1–2ms |
| Batch status (20 calcs, miss, write-back) | Pipeline of 20 `HSET` + `EXPIRE` | ~2–3ms |
| SLA registration | `ZADD` + `HSET` | ~2ms |
| SLA detection scan (N overdue runs) | `ZRANGEBYSCORE` + N `HGET` | 1ms + 0.5ms × N |
| Analytics cache get | 1 `GET` | ~0.5ms |
| Analytics cache invalidation | N `DEL` via index set | ~1–5ms (N keys) |

---

## Partition Query Cost

| Query | Partitions Scanned | Expected Extra Latency |
|-------|--------------------|------------------------|
| DAILY status (recent 3 days) | ≤ 4 | Negligible |
| MONTHLY status (last 13 months) | ~395 | +50–200ms vs DAILY |
| `findById(String)` — no date | ~455 | +200–500ms (cold), potentially seconds |
| Analytics (N days window) | 0 — reads `calculator_sli_daily` | None (not partitioned) |
| `run-performance` (N days) | ≤ N | Scales linearly with `days` |

---

## Scalability Limits

| Limit | Current State | Mitigation |
|-------|--------------|------------|
| Single Redis instance | No clustering / Sentinel configured | All cache bypasses to PG on Redis failure |
| MONTHLY partition scan (~395) | Structurally unavoidable with daily partitions | 60s cache TTL absorbs repeated MONTHLY queries |
| Async pool (10 max threads) | Queues under sustained high `completeRun()` rate | Increase `max-size` and `queue-capacity` if needed |
| Daily aggregate concurrency (TD-3) | Running average not concurrency-safe | Low risk at expected throughput |
| Alert delivery (log-only) | No external channel | Redis pool won't saturate; risk increases if HTTP calls added |

---

## Run Performance - Special Case

The `run-performance` endpoint reads from `calculator_runs` directly (not the pre-aggregated table), with a LEFT JOIN to `sla_breach_events`:

```sql
SELECT cr.*, sbe.severity
FROM calculator_runs cr
LEFT JOIN sla_breach_events sbe ON sbe.run_id = cr.run_id AND sbe.tenant_id = cr.tenant_id
WHERE cr.calculator_id = ? AND cr.tenant_id = ? AND cr.frequency = ?
AND cr.reporting_date >= CURRENT_DATE - CAST(? AS INTEGER) * INTERVAL '1 day'
AND cr.reporting_date <= CURRENT_DATE
ORDER BY cr.reporting_date ASC, cr.created_at ASC
```

- For `days=30`: ~30 rows, JOIN is O(30), fast index scan
- For `days=365`: ~365 rows, JOIN is O(365), borderline OLTP
- The JOIN is on `run_id` which is `UNIQUE` on `sla_breach_events` — effectively O(1) per row

**Recommendation:** Use `days ≤ 90` for real-time dashboard polling. Use `days=365` sparingly (nightly batch, not live refresh).

---

## Batch Status — Performance Guidance

| Scenario | Recommendation |
|----------|---------------|
| Dashboard polling (≤20 calcs) | `allowStale=true` (default). Fully cached, ~2ms. |
| Monitoring alert check (≤50 calcs) | `allowStale=true`. Acceptable. |
| Bulk reconciliation (100 calcs, DAILY) | `allowStale=false` if freshness is critical. One DB query with window function. |
| Bulk reconciliation (100 calcs, MONTHLY) | Avoid `allowStale=false`. 100 MONTHLY cache misses = 100 DB queries × ~395 partition scans each. Use `allowStale=true` and accept up to 60s staleness. |

