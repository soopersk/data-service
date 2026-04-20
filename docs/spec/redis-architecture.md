# Redis Architecture

Redis serves as a write-through read-accelerator. PostgreSQL is always the authoritative source of truth.

---

## Source-of-Truth Hierarchy

```
PostgreSQL → Redis (write-through)
         ↑
         └── Redis miss → DB query → re-populate Redis
```

On any Redis failure, all reads fall back to PostgreSQL transparently. Redis write failures are logged and do not block the DB write.

---

## Key Pattern Reference

| Key Pattern | Structure | TTL | Purpose |
|-------------|-----------|-----|---------|
| `obs:runs:zset:{calcId}:{tenantId}:{frequency}` | Sorted Set | 5m / 15m / 1h / 4h | Recent run objects, scored by `createdAt` epoch ms, capped at 100 members |
| `obs:status:hash:{calcId}:{tenantId}:{frequency}` | Hash | 30s / 60s | `CalculatorStatusResponse` objects, keyed by `historyLimit` integer |
| `obs:running` | Set | 2h | `{calcId}:{tenantId}:{frequency}` strings for currently RUNNING runs |
| `obs:active:bloom` | Set | 24h | Calculator IDs seen in last 24h (simulated bloom filter) |
| `obs:sla:deadlines` | Sorted Set | 24h | Member = `{tenantId}:{runId}:{reportingDate}`, score = SLA deadline epoch ms |
| `obs:sla:run_info` | Hash | 24h | Field = runKey, value = JSON `{runId, calcId, tenantId, reportingDate, startTime, slaTime}` |
| `obs:analytics:{prefix}:{calcId}:{tenantId}:{days}` | String (JSON) | 5m | Analytics responses without frequency dimension |
| `obs:analytics:{prefix}:{calcId}:{tenantId}:{freq}:{days}` | String (JSON) | 5m | Analytics responses with frequency dimension |
| `obs:analytics:index:{calcId}:{tenantId}` | Set | 1h | Tracks all analytics keys for bulk invalidation |
| `obs:analytics:regional-batch:history:{tenantId}:{reportingDate}` | String (JSON) | 24h | 7-day regional batch timing history — immutable once written |
| `obs:analytics:regional-batch:history:{tenantId}:{reportingDate}:{runNumber}` | String (JSON) | 24h | Run-number-scoped history variant |
| `obs:analytics:regional-batch:status:{tenantId}:{reportingDate}` | String (JSON) | 30s–4h | Full `RegionalBatchStatusResponse` — smart TTL |
| `obs:analytics:regional-batch:status:{tenantId}:{reportingDate}:{runNumber}` | String (JSON) | 30s–4h | Run-number-scoped status variant |
| `obs:analytics:dashboard:status:{tenantId}:{reportingDate}:{frequency}:{runNumber}` | String (JSON) | 30s–4h | Full `CalculatorDashboardResponse` — smart TTL |

---

## Key Details

### `obs:runs:zset` — Run ZSET

**Purpose:** Stores recent run objects for fast retrieval, avoiding DB queries on every status request.

**Write path:** `RedisCalculatorCache.cacheRunOnWrite()`
- Serialises run as JSON
- `ZADD` with score = `createdAt.toEpochMilli()`
- `ZREMRANGEBYRANK` to trim to last 100 members

**TTL logic (set at write time):**

| Run State | TTL |
|-----------|-----|
| `RUNNING` | 5 minutes |
| Completed < 30 minutes ago | 15 minutes |
| Completed DAILY (older) | 1 hour |
| Completed MONTHLY (older) | 4 hours |

**Read path:** `getRecentRuns()` — `ZREVRANGE 0 limit-1`. Returns `Optional.empty()` on miss (key absent or deserialization failure).

**Update path:** `updateRunInCache()` — removes old member by value, re-adds updated version at the original score. Does **not** reset the TTL.

**Eviction:** `evictRecentRuns()` — `DEL` the entire key. Called by `CacheWarmingService` on run events.

---

### `obs:status:hash` — Status Response Cache

**Purpose:** Caches full `CalculatorStatusResponse` objects to avoid assembling status from raw runs on every query.

**Write path:** `cacheStatusResponse()` — `HSET hash historyLimit responseObject` + `EXPIRE`

**TTL logic:**

| Condition | TTL |
|-----------|-----|
| Current run is RUNNING | 30 seconds |
| Current run is completed | 60 seconds |

**Read path:** `getStatusResponse()` — `HGET hash historyLimit`. Returns `Optional.empty()` on miss.

**Eviction:** `evictStatusResponse()` — `DEL` the entire hash (evicts all `historyLimit` variants at once). Called on run state transitions.

**Batch variant:** Uses Redis pipelining — all `HGET` commands for multiple calculators are sent in a single round trip.

---

### `obs:sla:deadlines` + `obs:sla:run_info` — SLA Monitoring

**Purpose:** Enables `LiveSlaBreachDetectionJob` to efficiently find runs whose SLA deadline has passed without querying the DB on every poll cycle.

**Write path (on `startRun()`):** Condition: `frequency == DAILY AND slaTime != null AND not already breached`
```
ZADD obs:sla:deadlines slaEpochMs {tenantId}:{runId}:{reportingDate}
HSET obs:sla:run_info {runKey} {json}
```
Both keys have a 24-hour TTL.

**Detection read path:** `ZRANGEBYSCORE obs:sla:deadlines 0 <nowEpochMs>` — returns all run keys with deadline ≤ now. For each key: `HGET obs:sla:run_info runKey` to get metadata.

**Early warning read path:** `ZRANGEBYSCORE obs:sla:deadlines <now> <now + 10min>` — returns runs approaching their deadline.

**Eviction:** `ZREM obs:sla:deadlines runKey` + `HDEL obs:sla:run_info runKey` — on run completion and after breach is confirmed.

---

### `obs:running` — Running Run Set

**Purpose:** Tracks which calculators currently have a RUNNING run. Used for bloom-filter-style existence checks.

**Write path:** `RedisCalculatorCache.cacheRunOnWrite()`
- `SADD obs:running {calcId}:{tenantId}:{frequency}` when `status == RUNNING` (2h TTL)
- `SREM obs:running {calcId}:{tenantId}:{frequency}` when status is anything else

---

### `obs:active:bloom` — Simulated Bloom Filter

**Purpose:** Lightweight calculator-ID existence check before expensive DB queries.

- Uses a plain Redis `Set` (no false positives — true set semantics)
- `SADD calculatorId` on write, `SISMEMBER` on read
- 24-hour TTL
- Stale IDs may persist until TTL expiry

---

### `obs:analytics:*` — Analytics Cache

**Purpose:** Avoids repeated aggregation queries for frequently-accessed analytics.

- TTL: **5 minutes** on all analytics keys
- `run-performance` keys are invalidated on `RunStartedEvent`
- All analytics keys are invalidated on `RunCompletedEvent` and `SlaBreachedEvent`
- `obs:analytics:index:{calcId}:{tenantId}` tracks all analytics keys for bulk invalidation (1h TTL)

**Key prefixes by endpoint:**

| Endpoint | Cache Key Prefix |
|----------|-----------------|
| `/runtime` | `obs:analytics:runtime:` |
| `/sla-summary` | `obs:analytics:sla-summary:` |
| `/trends` | `obs:analytics:trends:` |
| `/run-performance` | `obs:analytics:run-perf:` |

---

### `obs:analytics:regional-batch:*` — Regional Batch Two-Tier Cache

Managed by `RegionalBatchCacheService`. All Redis exceptions are swallowed — caching is best-effort.

**Tier 1 — History Cache:**
- Key: `obs:analytics:regional-batch:history:{tenantId}:{reportingDate}` (or `:{runNumber}` for run-scoped)
- TTL: 24 hours (historical timing data is immutable once all runs complete)
- Content: `List<RegionalBatchTiming>` — 7 days of `(region, reportingDate, startTime, endTime)` rows
- Written once on first cache miss; the 7-partition DB scan fires at most once per reporting date

**Tier 2 — Status Response Cache:**
- Key: `obs:analytics:regional-batch:status:{tenantId}:{reportingDate}` (or `:{runNumber}`)
- Content: Full serialized `RegionalBatchStatusResponse`
- TTL selected dynamically by `RegionalBatchCacheService.determineTtl()`:

| State | Condition | TTL |
|-------|-----------|-----|
| `TERMINAL_CLEAN` | 0 running, 0 not-started, 0 failed | 4 hours |
| `TERMINAL_WITH_FAILURES` | 0 running, 0 not-started, ≥1 failed | 5 minutes |
| `ACTIVE` | ≥1 running | 30 seconds |
| `NOT_STARTED` | 0 runs found | 60 seconds |

---

### `obs:analytics:dashboard:status:*` — Calculator Dashboard Cache

Managed by `DashboardCacheService`. Same smart TTL tiers as the regional batch status cache above.

- Key: `obs:analytics:dashboard:status:{tenantId}:{reportingDate}:{frequency}:{runNumber}`
- Content: Full serialized `CalculatorDashboardResponse`
- `frequency` and `runNumber` are always part of the key (Run 1 and Run 2 are separate cache entries)

---

## Serialization

All Redis values use `Jackson2JsonRedisSerializer<Object>` with:

- `JavaTimeModule` enabled (ISO-8601 timestamps)
- `WRITE_DATES_AS_TIMESTAMPS` disabled (strings, not epoch numbers)
- `FAIL_ON_UNKNOWN_PROPERTIES` disabled
- `DefaultTyping.NON_FINAL` with polymorphic type validation (type information embedded in JSON for correct deserialization)

Keys use `StringRedisSerializer`.

---

## Consistency Guarantees

| Scenario | Behaviour |
|----------|-----------|
| Redis write fails after DB commit | Cache miss on next read; DB queried; eventual consistency restored |
| Redis stale after crash/restart | All keys have TTLs; data rebuilt on cache miss |
| Concurrent writes to same run | `updateRunInCache()` is not atomic (`ZREM` + `ZADD`). A read between the two ops sees a miss and falls back to DB |
| Status hash eviction on state change | Whole hash deleted; next read re-populates from DB |
| Redis unreachable | All reads fall through to PostgreSQL; writes log error and continue |

---

## Redis Latency Estimates

| Operation | Redis Commands | Estimated Latency |
|-----------|---------------|-------------------|
| Status cache read (hit) | `HGET` | ~0.5ms |
| Status cache write | `HSET` + `EXPIRE` | ~1ms |
| Batch status (20 calcs, cached) | Pipeline of 20 `HGET` | ~1–2ms |
| Batch status (20 calcs, miss) | Pipeline of 20 `HSET` + `EXPIRE` | ~2–3ms |
| SLA register | `ZADD` + `HSET` | ~2ms |
| SLA detection scan (N overdue runs) | `ZRANGEBYSCORE` + N `HGET` | 1ms + 0.5ms × N |
| Analytics cache get | 1 `GET` | ~0.5ms |

---

## Configuration

Redis connection is configured via `spring.data.redis.*`:

| Property | Default | Description |
|----------|---------|-------------|
| `host` | `localhost` | Redis host (via `REDIS_HOST`) |
| `port` | `6379` | Redis port (via `REDIS_PORT`) |
| `password` | _(empty)_ | Redis password (via `REDIS_PASSWORD`) |
| `timeout` | `3000ms` | Command timeout |
| Lettuce pool max-active | `10` | Max connections |
| Lettuce pool max-idle | `5` | Max idle connections |
| Lettuce pool min-idle | `2` | Min idle connections |

Lettuce provides automatic reconnection on connection loss.

---

## Scaling Limits

- **Single Redis instance** — no clustering or Sentinel configured. Redis failure causes all caching to bypass to PostgreSQL.
- **No Redis metrics** — Redis command latency, connection pool usage, and key memory are not currently tracked via Micrometer. See [TD-9 / observability gaps](tech-debt.md).
