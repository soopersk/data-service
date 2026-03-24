# Analytics API

The Analytics API provides pre-aggregated trend data, SLA summaries, breach detail logs, and composite performance cards. All endpoints read from the `calculator_sli_daily` aggregate table (except `performance-card`) and are backed by a 5-minute Redis cache.

---

## Common Headers and Cache Behaviour

All analytics endpoints require:

```http
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
```

All analytics endpoints return `Cache-Control: max-age=60, private` except **SLA Breaches Detail** which returns `Cache-Control: no-cache`.

Analytics cache keys have a **5-minute TTL** and are invalidated automatically when a run for the calculator completes or an SLA breach is detected.

---

## GET /api/v1/analytics/calculators/{calculatorId}/runtime

Average runtime statistics over a lookback period.

### Query Parameters

| Parameter | Type | Required | Constraints | Description |
|-----------|------|----------|-------------|-------------|
| `days` | int | Yes | Min 1, Max 365 | Lookback window in calendar days |
| `frequency` | String | No | `DAILY` or `MONTHLY` | Default: `DAILY` |

### Response

```json
{
  "calculatorId": "calc-daily-fx",
  "periodDays": 30,
  "frequency": "DAILY",
  "avgDurationMs": 4200000,
  "avgDurationFormatted": "1hr 10mins",
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
    },
    {
      "date": "2026-03-23",
      "avgDurationMs": 3840000,
      "totalRuns": 1,
      "successRuns": 1
    }
  ]
}
```

### cURL Example

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/runtime?days=30&frequency=DAILY"
```

---

## GET /api/v1/analytics/calculators/{calculatorId}/sla-summary

SLA breach count breakdown by severity and traffic-light classification (GREEN / AMBER / RED).

### Query Parameters

| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| `days` | int | Yes | Min 1, Max 365 |

### Response

```json
{
  "calculatorId": "calc-daily-fx",
  "periodDays": 30,
  "totalBreaches": 3,
  "greenDays": 27,
  "amberDays": 2,
  "redDays": 1,
  "breachesBySeverity": {
    "LOW": 1,
    "MEDIUM": 1,
    "HIGH": 0,
    "CRITICAL": 1
  },
  "breachesByType": {
    "END_TIME_EXCEEDED": 2,
    "RUN_FAILED": 1
  }
}
```

### Day Classification

| Colour | Condition |
|--------|-----------|
| **GREEN** | Zero SLA breaches on that day |
| **RED** | At least one breach with severity `HIGH` or `CRITICAL` |
| **AMBER** | Breaches exist but none are `HIGH` or `CRITICAL` |

### cURL Example

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/sla-summary?days=30"
```

---

## GET /api/v1/analytics/calculators/{calculatorId}/trends

Per-day trend data including run counts, durations, breach counts, and CET timing offsets.

### Query Parameters

| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| `days` | int | Yes | Min 1, Max 365 |

### Response

```json
{
  "calculatorId": "calc-daily-fx",
  "periodDays": 30,
  "trends": [
    {
      "date": "2026-03-24",
      "avgDurationMs": 4200000,
      "totalRuns": 1,
      "successRuns": 1,
      "slaBreaches": 0,
      "avgStartMinCet": 300,
      "avgEndMinCet": 370,
      "slaStatus": "GREEN"
    },
    {
      "date": "2026-03-20",
      "avgDurationMs": 5400000,
      "totalRuns": 1,
      "successRuns": 1,
      "slaBreaches": 1,
      "avgStartMinCet": 298,
      "avgEndMinCet": 420,
      "slaStatus": "RED"
    }
  ]
}
```

!!! tip "CET timing fields"
    `avgStartMinCet` and `avgEndMinCet` are minutes since midnight CET (0–1439). Divide by 60 to get decimal hours. For example, `300` = 05:00 CET, `370` = 06:10 CET.

### cURL Example

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/trends?days=30"
```

---

## GET /api/v1/analytics/calculators/{calculatorId}/sla-breaches

Paginated detailed breach event log. Returns individual breach records with full metadata.

**Note:** This endpoint returns `Cache-Control: no-cache` — data is always fresh.

### Query Parameters

| Parameter | Type | Required | Constraints | Description |
|-----------|------|----------|-------------|-------------|
| `days` | int | Yes | Min 1, Max 365 | Lookback window |
| `severity` | String | No | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` | Filter by severity |
| `page` | int | No | Min 0 | Zero-based page offset (offset pagination) |
| `cursor` | String | No | Opaque Base64 | Cursor for efficient keyset pagination |
| `size` | int | No | Min 1, Max 100 | Page size. Default: 20 |

### Pagination Modes

**Cursor-based (recommended for large result sets):**

Use the `nextCursor` from the previous response as the `cursor` parameter on the next request. This uses an indexed keyset scan and is O(1) regardless of page depth.

```bash
# First page
curl -u admin:admin -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/sla-breaches?days=90&size=20"

# Next page using cursor from response
curl -u admin:admin -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/sla-breaches?days=90&size=20&cursor=MjAyNi0wMS0yNVQxNDoxNTowMFo6NDI="
```

**Offset-based (for simple use cases):**

```bash
curl -u admin:admin -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/sla-breaches?days=30&page=0&size=20"
```

!!! warning "Large offset pagination"
    Offset pagination with `page > 50` against large `days` windows causes a full index scan to the offset position. For page depths beyond 50, always use cursor-based pagination.

### Response

```json
{
  "content": [
    {
      "breachId": 42,
      "runId": "run-fx-20260320-001",
      "calculatorId": "calc-daily-fx",
      "calculatorName": "FX Rate Calculator",
      "breachType": "END_TIME_EXCEEDED",
      "severity": "HIGH",
      "slaStatus": "RED",
      "expectedValue": 1742979000000,
      "actualValue": 1742982600000,
      "createdAt": "2026-03-20T07:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1,
  "nextCursor": "MjAyNi0wMy0yMFQwNzowMDowMFo6NDI="
}
```

#### Breach Type Values

| Breach Type | Description |
|-------------|-------------|
| `END_TIME_EXCEEDED` | Run completed after the SLA deadline |
| `DURATION_EXCEEDED` | Run took more than 150% of the expected duration |
| `RUN_FAILED` | Run completed with `FAILED` or `TIMEOUT` status |
| `STILL_RUNNING_PAST_SLA` | Run detected still RUNNING after SLA deadline by the live detection job |

---

## GET /api/v1/analytics/calculators/{calculatorId}/performance-card

Composite dashboard payload. Provides schedule information, SLA compliance percentages, mean duration, per-run chart data, and reference lines for visualisation.

### Query Parameters

| Parameter | Type | Required | Constraints | Description |
|-----------|------|----------|-------------|-------------|
| `days` | int | No | Min 1, Max 365 | Lookback window. Default: `30` |
| `frequency` | String | No | `DAILY` or `MONTHLY` | Default: `DAILY` |

### Response

```json
{
  "calculatorId": "calc-daily-fx",
  "calculatorName": "FX Rate Calculator",
  "schedule": {
    "estimatedStartTimeCet": "05:00",
    "frequency": "DAILY"
  },
  "periodDays": 30,
  "meanDurationMs": 4200000,
  "meanDurationFormatted": "1hr 10mins",
  "slaSummary": {
    "totalRuns": 22,
    "slaMetCount": 19,
    "slaMetPct": 86.4,
    "lateCount": 2,
    "latePct": 9.1,
    "veryLateCount": 1,
    "veryLatePct": 4.5
  },
  "runs": [
    {
      "runId": "run-fx-20260324-001",
      "reportingDate": "2026-03-24",
      "startHourCet": 5.0,
      "endHourCet": 6.17,
      "slaStatus": "SLA_MET",
      "durationMs": 4200000,
      "status": "SUCCESS"
    }
  ],
  "referenceLines": {
    "slaStartHourCet": 5.0,
    "slaEndHourCet": 6.5
  }
}
```

#### SLA Status Values (Per-Run)

| Value | Description |
|-------|-------------|
| `SLA_MET` | Run completed within SLA |
| `LATE` | Breach with severity `LOW` or `MEDIUM` |
| `VERY_LATE` | Breach with severity `HIGH` or `CRITICAL` |

!!! note "Percentage invariant"
    `slaMetPct + latePct + veryLatePct = 100.0` always holds.

### cURL Example

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/performance-card?days=30&frequency=DAILY"
```

!!! warning "Performance card with large windows"
    `performance-card` queries raw `calculator_runs` directly (not the pre-aggregated table) and JOINs to `sla_breach_events`. For `days=365`, this returns up to 365 raw rows. Acceptable for `days ≤ 90`. For `days=365` with no cache, expect 50–300ms.

---

## Analytics Cache Invalidation

Analytics cache keys are automatically invalidated when:

- A run for the calculator **completes** (`RunCompletedEvent`)
- An SLA breach is detected for the calculator (`SlaBreachedEvent`)

Cache keys expire after **5 minutes** regardless. No manual cache invalidation is required.
