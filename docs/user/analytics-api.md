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
- Run started: evict `run-performance` cache keys only
- Run completed: evict all analytics cache keys for calculator+tenant
- SLA breached: evict all analytics cache keys for calculator+tenant

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

## GET /api/v1/analytics/projections/calculators/{calculatorId}/performance-card

Pre-formatted projection for dashboard consumers.

This endpoint composes from `run-performance` data and adds formatting (CET labels, chart coordinates, percentages).
