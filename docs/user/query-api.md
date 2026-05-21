# Query API

The query API provides current status and recent history for calculators. It is backed by a Redis write-through cache, making it suitable for high-frequency polling from dashboards and monitoring systems.

---

## GET /api/v1/calculators/{calculatorId}/status

Returns the current run status and recent history for a single calculator.

### Request

```
GET /api/v1/calculators/{calculatorId}/status?frequency=DAILY&historyLimit=5
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
```

#### Path Parameter

| Parameter | Description |
|-----------|-------------|
| `calculatorId` | The calculator identifier |

#### Query Parameters

| Parameter | Type | Default | Constraints | Description |
|-----------|------|---------|-------------|-------------|
| `frequency` | String | Required | `DAILY`, `D`, `MONTHLY`, `M` | Frequency of runs to query |
| `historyLimit` | int | `5` | Min 1, Max 100 | Number of historical runs to return |
| `bypassCache` | boolean | `false` | | Force a fresh database query, bypassing Redis |

### Response

**`200 OK`**, `Cache-Control: max-age=30, private`

When `bypassCache=true`, `Cache-Control: no-cache` is returned instead.

```json
{
  "calculatorName": "FX Rate Calculator",
  "lastRefreshed": "2026-03-24T06:15:00Z",
  "current": {
    "runId": "run-fx-20260324-001",
    "status": "SUCCESS",
    "startTime": "2026-03-24T05:00:00Z",
    "endTime": "2026-03-24T06:10:00Z",
    "durationMs": 4200000,
    "slaBreached": false,
    "slaBreachReason": null,
    "reportingDate": "2026-03-24"
  },
  "history": [
    {
      "runId": "run-fx-20260323-001",
      "status": "SUCCESS",
      "startTime": "2026-03-23T05:01:00Z",
      "endTime": "2026-03-23T06:05:00Z",
      "durationMs": 3840000,
      "slaBreached": false,
      "reportingDate": "2026-03-23"
    }
  ]
}
```

### Cache Behaviour

Status responses are cached in Redis under `obs:status:hash:{calcId}:{tenantId}:{frequency}`, keyed by `historyLimit`.

| Run State | Cache TTL |
|-----------|-----------|
| Currently RUNNING | 30 seconds |
| Completed | 60 seconds |

A cache miss falls back to a partition-pruned SQL query:
- DAILY: scans at most 4 partitions (last 3 days + today)
- MONTHLY: scans ~395 partitions (see [Performance Notes](#performance-notes))

### cURL Example

```bash
# Default: 5 most recent runs, from cache
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/calculators/calc-daily-fx/status?frequency=DAILY"

# 10 most recent runs, bypassing cache
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/calculators/calc-daily-fx/status?frequency=DAILY&historyLimit=10&bypassCache=true"
```

---

## POST /api/v1/calculators/batch/status

Returns status for multiple calculators in a single request. Uses Redis pipelining for efficient cache lookup.

### Request

```
POST /api/v1/calculators/batch/status?frequency=DAILY&historyLimit=5
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
Content-Type: application/json
```

#### Query Parameters

| Parameter | Type | Default | Constraints | Description |
|-----------|------|---------|-------------|-------------|
| `frequency` | String | Required | `DAILY`, `D`, `MONTHLY`, `M` | Frequency of runs to query |
| `historyLimit` | int | `5` | Min 1, Max 50 | Historical runs per calculator |
| `allowStale` | boolean | `true` | | When `false`, forces a DB query for all calculators |

#### Request Body

A JSON array of calculator IDs:

```json
["calc-daily-fx", "calc-daily-rates", "calc-monthly-pnl"]
```

Constraints: Not empty, maximum 100 IDs.

### Response

**`200 OK`**, `Cache-Control: max-age=60, private`

When `allowStale=false`, `Cache-Control: no-cache` is returned.

A JSON array of `CalculatorStatusResponse` objects (same structure as the single-calculator endpoint):

```json
[
  {
    "calculatorName": "FX Rate Calculator",
    "lastRefreshed": "2026-03-24T06:15:00Z",
    "current": { "runId": "run-fx-20260324-001", "status": "SUCCESS", ... },
    "history": [...]
  },
  {
    "calculatorName": "Daily Rates Calculator",
    "lastRefreshed": "2026-03-24T06:20:00Z",
    "current": { "runId": "run-rates-20260324-001", "status": "RUNNING", ... },
    "history": [...]
  }
]
```

### Cache Behaviour

The batch endpoint uses Redis pipelining:

1. Sends all `HGET` commands for the requested calculator IDs in a single pipeline
2. Identifies cache misses
3. Issues a single SQL query with a `ROW_NUMBER()` window function for all misses
4. Populates cache for the misses

For batches of ≤20 calculators with warm cache, expected latency is ~1–2ms.

### cURL Example

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  -H "Content-Type: application/json" \
  -X POST \
  "http://localhost:8080/api/v1/calculators/batch/status?frequency=DAILY&historyLimit=5" \
  -d '["calc-daily-fx", "calc-daily-rates", "calc-monthly-pnl"]'
```

---

## GET /api/v1/calculators/batch/runs

Returns the dimensional run state (one entry per region or run-type) for a set of calculators on a specific reporting date. Powers the multi-section dashboard.

Unlike `/{calculatorId}/status` and `/batch/status`, this endpoint identifies calculators by their **readable `calculator_name`** — not the upstream UUID `calculator_id`. The UUID is internal and never appears in the request or response.

### Request

```
GET /api/v1/calculators/batch/runs?reporting_date=2026-05-19&frequency=DAILY&run_number=1&keys=capitalcalc|portfoliocalc
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
```

#### Query Parameters

| Parameter | Type | Default | Constraints | Description |
|-----------|------|---------|-------------|-------------|
| `reporting_date` | Date | Required | ISO `YYYY-MM-DD` | Partition key |
| `frequency` | String | `DAILY` | `DAILY` or `MONTHLY` | |
| `run_number` | String | none | `1` or `2` when provided | Omit to return all buckets |
| `keys` | String | Required | Non-blank | Pipe-separated `calculator_name` values (no wildcards, no UUIDs) |

### Response

**`200 OK`**, `Cache-Control: max-age=30, private`

```json
{
  "reportingDate": "2026-05-19",
  "frequency": "DAILY",
  "runNumber": "1",
  "generatedAt": "2026-05-19T17:00:00Z",
  "calculators": {
    "capitalcalc": {
      "calculatorName": "capitalcalc",
      "runs": [
        {
          "runId": "run-wmap-001",
          "region": "WMAP",
          "status": "SUCCESS",
          "slaStatus": "ON_TIME",
          "startTime": "2026-05-19T13:02:00Z",
          "endTime": "2026-05-19T14:45:00Z",
          "estimatedStartTime": "2026-05-19T13:00:00Z",
          "estimatedEndTime": "2026-05-19T14:50:00Z",
          "sla": "2026-05-19T15:00:00Z",
          "durationMs": 6180000,
          "slaBreached": false,
          "isRerun": false
        },
        {
          "runId": "run-ldnl-002",
          "region": "LDNL",
          "status": "FAILED",
          "slaStatus": "FAILED",
          "sla": "2026-05-19T15:00:00Z",
          "slaBreached": true,
          "slaBreachReason": "Run status: FAILED",
          "isRerun": true
        }
      ]
    },
    "portfoliocalc": { "calculatorName": "portfoliocalc", "runs": [] }
  }
}
```

#### Notes

- The `calculators` map key is the `calculator_name` you sent in `keys`.
- `runs` is an empty list when no run exists for that calculator on the given date (the key is still present).
- Each run entry exposes one of `region` or `runType` (regional calculators use `region`; typed calculators use `runType`) — never both.
- `isRerun: true` indicates that dimensional run was re-triggered (the UI renders the dimension label with a `*` suffix and a failed icon).
- Null fields on `RunEntry` are omitted from JSON.
- `estimatedStartTime` / `estimatedEndTime` / `sla` carry the run's stored values, set at start by precedence: **request value (Airflow) → cached historical profile (avg start / avg duration) → computed (`start + expectedDurationMs`)**. `sla` is the duration-derived deadline (`startTime + avgDuration × (1 + thresholdPercent) + lateBand`), not an upstream-supplied wall-clock time.

### Server-Side Behaviour

The service two-phase-groups raw rows:

1. **Phase 1** — Rows sharing a `correlation_id` (parallel splits) are collapsed into one entry using worst-status-wins precedence.
2. **Phase 2** — Standalone rows (null `correlation_id`) are deduplicated by `(region, runType)`; the latest by `created_at` wins, and `isRerun=true` is set when more than one attempt exists for that dimension.

### cURL Example

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-05-19&frequency=DAILY&run_number=1&keys=capitalcalc|portfoliocalc|grportfoliocalc"
```

---

## Performance Notes

### DAILY vs MONTHLY Query Cost

| Scenario | Partition Scan | Typical Latency |
|----------|---------------|-----------------|
| DAILY, cached | 0 (Redis) | ~0.5ms |
| DAILY, uncached | 4 partitions | ~5–15ms |
| MONTHLY, cached | 0 (Redis) | ~0.5ms |
| MONTHLY, uncached | ~395 partitions | ~50–200ms |

!!! warning "MONTHLY uncached queries"
    MONTHLY queries without a cache hit scan ~395 daily partitions because the end-of-month filter cannot be evaluated at partition exclusion time. For MONTHLY calculators with high query rates, the 60-second cache TTL is critical. Avoid `bypassCache=true` / `allowStale=false` for MONTHLY calculators in high-traffic scenarios.

### Batch Size Guidelines

| Batch Size | `allowStale=true` (cached) | `allowStale=false` (DB) |
|------------|---------------------------|------------------------|
| 1–20 | Optimal | Acceptable |
| 21–50 | Good | Monitor latency |
| 51–100 | Acceptable | Not recommended for MONTHLY |

---

## Run Status Values

| Status | Description |
|--------|-------------|
| `RUNNING` | Run is currently in progress |
| `SUCCESS` | Run completed successfully |
| `FAILED` | Run failed |
| `TIMEOUT` | Run timed out |
| `CANCELLED` | Run was cancelled |
