# Analytics API

Canonical analytics endpoints return raw domain data. Pre-formatted dashboard shapes are exposed under the projections route.

## Common Headers and Cache Behaviour

All analytics endpoints require:

```http
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
```

- `Cache-Control: max-age=60, private` for cached endpoints
- `Cache-Control: no-cache` for SLA breach detail endpoint
- Redis analytics cache TTL: 5 minutes

Cache invalidation:
- Run started: evict `run-performance` and `executions` cache keys only
- Run completed: evict all analytics cache keys for the calculator (both UUID-index and name-index)
- SLA breached: evict all analytics cache keys for the calculator (both UUID-index and name-index)

!!! note "End-of-day freshness"
    The aggregate-backed endpoints — `/runtime`, `/sla-summary`, `/trends` — read from `calculator_sli_daily`, which is now rebuilt by a **nightly batch** (`DailyAggregationJob`) rather than on every run completion. They therefore reflect data **through the last completed day**; the current day's runs appear after the next nightly run. Live, per-run views (`/run-performance`, `/executions`, and the query API) read raw `calculator_runs` and are unaffected.

---

## GET /api/v1/analytics/calculators/{calculatorId}/runtime

Average runtime statistics over a lookback period.

Query params:
- `days` (required, 1-365)
- `frequency` (optional, `DAILY` or `MONTHLY`, default `DAILY`)

Response (raw):

```json
{
  "calculatorId": "calc-daily-fx",
  "periodDays": 30,
  "frequency": "DAILY",
  "avgDurationMs": 4200000,
  "minDurationMs": 3600000,
  "maxDurationMs": 5400000,
  "totalRuns": 22,
  "successRate": 0.955,
  "dataPoints": [
    {
      "date": "2026-03-24",
      "avgDurationMs": 4200000,
      "totalRuns": 1,
      "successRuns": 1
    }
  ]
}
```

---

## GET /api/v1/analytics/calculators/{calculatorId}/sla-summary

SLA breach count breakdown by severity and traffic-light classification.

---

## GET /api/v1/analytics/calculators/{calculatorId}/trends

Per-day trend data including run counts, durations, breach counts, and CET minute offsets.

---

## GET /api/v1/analytics/calculators/{calculatorId}/sla-breaches

Paginated detailed breach event log.

Query params:
- `days` (required, 1-365)
- `severity` (optional)
- `page` (optional, offset mode)
- `cursor` (optional, keyset mode)
- `size` (optional, 1-100)

This endpoint always returns fresh data (`Cache-Control: no-cache`).

---

## GET /api/v1/analytics/calculators/{calculatorId}/run-performance

Raw run-level performance data.

Query params:
- `days` (optional, 1-365, default `30`)
- `frequency` (optional, `DAILY` or `MONTHLY`, default `DAILY`)

Response (raw):

```json
{
  "calculatorId": "calc-daily-fx",
  "calculatorName": "FX Rate Calculator",
  "frequency": "DAILY",
  "periodDays": 30,
  "meanDurationMs": 4200000,
  "totalRuns": 22,
  "runningRuns": 1,
  "slaMetCount": 19,
  "lateCount": 2,
  "veryLateCount": 1,
  "runs": [
    {
      "runId": "run-fx-20260324-001",
      "reportingDate": "2026-03-24",
      "startTime": "2026-03-24T04:00:00Z",
      "endTime": "2026-03-24T05:10:00Z",
      "durationMs": 4200000,
      "status": "SUCCESS",
      "slaBreached": false,
      "slaStatus": "SLA_MET"
    },
    {
      "runId": "run-fx-20260325-001",
      "reportingDate": "2026-03-25",
      "startTime": "2026-03-25T04:00:00Z",
      "endTime": null,
      "durationMs": null,
      "status": "RUNNING",
      "slaBreached": false,
      "slaStatus": "RUNNING"
    }
  ],
  "estimatedStartTime": "2026-03-24T04:00:00Z",
  "slaTime": "2026-03-24T05:15:00Z"
}
```

Run-level SLA values:
- `SLA_MET`
- `LATE`
- `VERY_LATE`
- `RUNNING`

Counter semantics:
- `totalRuns` is terminal/evaluated runs only
- `runningRuns` counts in-progress runs
- RUNNING rows are included in `runs` but excluded from SLA counters

---

## GET /api/v1/analytics/calculators/{calculatorName}/executions

Raw run-by-run execution history for the performance card. Two key differences from `/run-performance`:

1. The path variable is the **readable `calculator_name`** (unique per tenant), not the upstream UUID `calculator_id`.
2. Split runs that share a `correlation_id` appear as **separate rows** — no logical grouping. `subRunIds` is always `null`. Use `/run-performance` for the grouped/logical view.

Query params:
- `days` (optional, 1-365, default `30`)
- `frequency` (optional, `DAILY` or `MONTHLY`, default `DAILY`)
- `run_number` (optional, any value; omit or pass `""` for all buckets; a value filters to that run_number only)

Response shape is identical to `/run-performance` (same `RunPerformanceData` envelope). The envelope's `calculatorId` and `calculatorName` fields both carry the readable name; no UUID appears because the lookup is by name. Each `RunDataPoint` includes:

- `durationMs` — actual wall-clock duration (null while RUNNING)
- `expectedDurationMs` — configured expected duration (immutable, set at first INSERT); use for actual-vs-expected variance comparison
- `runNumber` — `"1"` or `"2"` (string, matches the DB column type)
- `subRunIds` — always `null` on this endpoint (no grouping)
- `estimatedStartTime` / `slaTime` — this run's own estimated start and duration-derived SLA deadline

#### Envelope reference lines (`estimatedStartTime`, `slaTime`)

The two **top-level** fields are chart reference lines for the performance card (a "typical start" line and an "SLA" line). They are sourced from the calculator's cached daily **profile** — the typical start (historical average start-of-day) and a typical buffered deadline (`avgDuration × (1 + thresholdPercent) + lateBand`) — so the lines stay stable across the window instead of tracking one run.

If the calculator has insufficient history (fewer than `min-sample-size` runs), the envelope falls back to the **most recent run's** stored `estimatedStartTime` / `slaTime`. Per-run values in each `RunDataPoint` are always that run's own.

Cache: `max-age=60, private` (HTTP). The response is also cached in Redis via `AnalyticsCacheService` with a **5-minute TTL** keyed by `obs:analytics:executions:{calculatorName}:{frequency}:{days}:{runNumber|all}`. Cache is invalidated on `RunStartedEvent` (evicts executions prefix) and on `RunCompletedEvent` / `SlaBreachedEvent` (evicts all analytics keys for the calculator). The per-run rows come from raw `calculator_runs` (live); the envelope reference lines come from the profile cache (refreshed nightly).

### cURL Example

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/analytics/calculators/portfoliocalc/executions?days=30"
```

---

## GET /api/v1/analytics/projections/calculators/{calculatorId}/performance-card

Pre-formatted projection for dashboard consumers.

This endpoint composes from `run-performance` data and adds formatting (CET labels, chart coordinates, percentages).
