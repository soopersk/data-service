# API Reference

**Base URL:** `http://localhost:8080` (local) / environment-specific (dev/prod)

**API Docs (live):** `GET /api-docs` | **Swagger UI:** `GET /swagger-ui.html`

---

## Authentication & Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Authorization` | Yes (non-local) | `Basic <base64(user:pass)>` |
| `X-Tenant-Id` | Always | Scopes all data to this tenant |
| `X-Request-ID` | Optional | Propagated in response; auto-generated UUID if absent |

### Unauthenticated Paths

`GET /api/v1/health`, `GET /swagger-ui/**`, `GET /api-docs`, `GET /v3/api-docs/**`

---

## Error Response Format

```json
{
  "timestamp": "2026-02-22T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "<exception message>"
}
```

Validation errors (field-level):

```json
{
  "timestamp": "2026-02-22T10:30:00Z",
  "status": 400,
  "error": "Validation Failed",
  "errors": {
    "runId": "must not be blank",
    "reportingDate": "must not be null"
  }
}
```

| HTTP Status | Cause |
|-------------|-------|
| `400` | Validation failure or business rule violation |
| `401` | Missing or invalid `Authorization` header |
| `403` | `tenantId` does not match the run's tenant |
| `404` | Run or calculator not found |
| `500` | Unhandled server error |

---

## Health

### `GET /api/v1/health`

No authentication required.

**Response `200 OK`:**
```json
{ "status": "UP" }
```

---

## Run Ingestion

### `POST /api/v1/runs/start`

Records a calculator run start. Returns `201 Created` for new runs, `200 OK` for duplicates.

**Request Body:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `runId` | String | Yes | Unique per tenant |
| `calculatorId` | String | Yes | |
| `calculatorName` | String | Yes | Immutable after first insert |
| `frequency` | String | Yes | `DAILY`/`D` or `MONTHLY`/`M` |
| `reportingDate` | Date | Yes | `YYYY-MM-DD` — partition key |
| `startTime` | Instant | Yes | UTC. Example: `2026-03-24T05:00:00Z` |
| `slaTimeCet` | LocalTime | Yes | CET time-of-day. Example: `06:30:00` |
| `expectedDurationMs` | Long | No | For 150% duration breach detection |
| `estimatedStartTimeCet` | LocalTime | No | CET estimated start for display |
| `runParameters` | Object | No | Stored as JSONB |
| `additionalAttributes` | Object | No | Stored as JSONB |

**Response Body (`RunResponse`):**

```json
{
  "runId": "run-abc-123",
  "calculatorId": "calc-1",
  "calculatorName": "My Calculator",
  "status": "RUNNING",
  "startTime": "2026-03-24T05:00:00Z",
  "endTime": null,
  "durationMs": null,
  "slaBreached": false,
  "slaBreachReason": null
}
```

**Idempotency:** Duplicate `(runId, reportingDate)` returns existing run. Counter `calculator.runs.start.duplicate` incremented.

**SLA start-time breach:** If `startTime > slaDeadline`, response includes `"slaBreached": true`.

---

### `POST /api/v1/runs/{runId}/complete`

Records run completion and evaluates SLA.

**Path Variable:** `runId`

**Request Body:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `endTime` | Instant | Yes | Must be after `startTime` |
| `status` | String | No | `SUCCESS`/`FAILED`/`TIMEOUT`/`CANCELLED`. Defaults to `SUCCESS` |

**Response Body:** Same as start — `RunResponse` with `endTime`, `durationMs`, `slaBreached`, `slaBreachReason` populated.

**Error cases:**

| Condition | HTTP Status | Error |
|-----------|-------------|-------|
| Run not found | `404` | Not Found |
| `tenantId` mismatch | `403` | Forbidden |
| `endTime` before `startTime` | `400` | Bad Request |

---

## Calculator Status

### `GET /api/v1/calculators/{calculatorId}/status`

Current status and recent history for one calculator.

**Query Parameters:**

| Name | Type | Default | Constraints |
|------|------|---------|-------------|
| `frequency` | String | Required | `DAILY`/`D`/`MONTHLY`/`M` |
| `historyLimit` | int | `5` | Min 1, Max 100 |
| `bypassCache` | boolean | `false` | |

**Response `200 OK`**, `Cache-Control: max-age=30, private`:

```json
{
  "calculatorName": "My Calculator",
  "lastRefreshed": "2026-03-24T06:15:00Z",
  "current": {
    "runId": "run-abc-123",
    "status": "RUNNING",
    "startTime": "2026-03-24T05:00:00Z",
    "endTime": null,
    "durationMs": null,
    "slaBreached": false,
    "slaBreachReason": null,
    "reportingDate": "2026-03-24"
  },
  "history": [ ... ]
}
```

---

### `POST /api/v1/calculators/batch/status`

Status for multiple calculators in a single request.

**Request Body:** `List<String>` — calculator IDs. Max 100.

**Query Parameters:**

| Name | Type | Default | Constraints |
|------|------|---------|-------------|
| `frequency` | String | Required | Same as above |
| `historyLimit` | int | `5` | Min 1, Max 50 |
| `allowStale` | boolean | `true` | `false` forces DB for all |

**Response `200 OK`**, `Cache-Control: max-age=60, private`:

`List<CalculatorStatusResponse>` — same shape as single-calculator endpoint, one element per input ID.

---

## Analytics

All analytics endpoints:
- Require `Authorization` + `X-Tenant-Id`
- Return `Cache-Control: max-age=60, private` (except SLA breaches: `no-cache`)
- Are backed by a 5-minute Redis analytics cache

### `GET /api/v1/analytics/calculators/{calculatorId}/runtime`

Average runtime statistics over a lookback window.

**Query Parameters:** `days` (required, 1–365), `frequency` (default `DAILY`)

**Data source:** `calculator_sli_daily`

**Response:**
```json
{
  "calculatorId": "calc-1",
  "periodDays": 30,
  "frequency": "DAILY",
  "avgDurationMs": 8100000,
  "avgDurationFormatted": "2hrs 15mins",
  "minDurationMs": 6000000,
  "maxDurationMs": 12000000,
  "totalRuns": 28,
  "successRate": 0.96,
  "dataPoints": [
    { "date": "2026-03-24", "avgDurationMs": 8100000, "totalRuns": 1, "successRuns": 1 }
  ]
}
```

---

### `GET /api/v1/analytics/calculators/{calculatorId}/sla-summary`

SLA breach count with GREEN/AMBER/RED day classification.

**Query Parameters:** `days` (required, 1–365)

**Data sources:** `calculator_sli_daily` + `sla_breach_events`

**Response:**
```json
{
  "calculatorId": "calc-1",
  "periodDays": 30,
  "totalBreaches": 5,
  "greenDays": 25,
  "amberDays": 4,
  "redDays": 1,
  "breachesBySeverity": { "LOW": 2, "MEDIUM": 1, "HIGH": 1, "CRITICAL": 1 },
  "breachesByType": { "END_TIME_EXCEEDED": 3, "DURATION_EXCEEDED": 1, "RUN_FAILED": 1 }
}
```

---

### `GET /api/v1/analytics/calculators/{calculatorId}/trends`

Per-day trend data with run counts, durations, SLA status.

**Query Parameters:** `days` (required, 1–365)

**Data source:** `calculator_sli_daily`

**Response:**
```json
{
  "calculatorId": "calc-1",
  "periodDays": 30,
  "trends": [
    {
      "date": "2026-03-24",
      "avgDurationMs": 8100000,
      "totalRuns": 1,
      "successRuns": 1,
      "slaBreaches": 0,
      "avgStartMinCet": 300,
      "avgEndMinCet": 435,
      "slaStatus": "GREEN"
    }
  ]
}
```

---

### `GET /api/v1/analytics/calculators/{calculatorId}/sla-breaches`

Paginated detailed breach event log. Always returns fresh data (`no-cache`).

**Query Parameters:**

| Name | Type | Default | Constraints |
|------|------|---------|-------------|
| `days` | int | Required | 1–365 |
| `severity` | String | Optional | `LOW`/`MEDIUM`/`HIGH`/`CRITICAL` |
| `page` | int | `0` | Min 0 |
| `cursor` | String | Optional | Opaque Base64 for keyset pagination |
| `size` | int | `20` | 1–100 |

**Response:**
```json
{
  "content": [
    {
      "breachId": 42,
      "runId": "run-abc-123",
      "calculatorId": "calc-1",
      "calculatorName": "My Calculator",
      "breachType": "END_TIME_EXCEEDED",
      "severity": "HIGH",
      "slaStatus": "RED",
      "expectedValue": 1706220000000,
      "actualValue": 1706224200000,
      "createdAt": "2026-01-25T14:15:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3,
  "nextCursor": "MjAyNi0wMS0yNVQxNDoxNTowMFo6NDI="
}
```

---

### `GET /api/v1/analytics/calculators/{calculatorId}/performance-card`

Composite dashboard payload: schedule info, SLA percentages, per-run bars, reference lines.

**Query Parameters:** `days` (default `30`, 1–365), `frequency` (default `DAILY`)

**Data source:** `calculator_runs` LEFT JOIN `sla_breach_events`

**Response:**
```json
{
  "calculatorId": "calc-1",
  "calculatorName": "My Calculator",
  "schedule": {
    "estimatedStartTimeCet": "10:00",
    "frequency": "DAILY"
  },
  "periodDays": 30,
  "meanDurationMs": 8100000,
  "meanDurationFormatted": "2hrs 15mins",
  "slaSummary": {
    "totalRuns": 28,
    "slaMetCount": 24,
    "slaMetPct": 85.7,
    "lateCount": 3,
    "latePct": 10.7,
    "veryLateCount": 1,
    "veryLatePct": 3.6
  },
  "runs": [
    {
      "runId": "run-abc-123",
      "reportingDate": "2026-01-24",
      "startHourCet": 10.12,
      "endHourCet": 12.25,
      "slaStatus": "SLA_MET",
      "durationMs": 7980000,
      "status": "SUCCESS"
    }
  ],
  "referenceLines": {
    "slaStartHourCet": 10.0,
    "slaEndHourCet": 12.0
  }
}
```

**SLA status per run:** `SLA_MET` (not breached), `LATE` (severity LOW/MEDIUM), `VERY_LATE` (severity HIGH/CRITICAL).

---

## OpenAPI Tags

| Tag | Endpoints |
|-----|-----------|
| `Run Ingestion` | `/api/v1/runs/start`, `/api/v1/runs/{runId}/complete` |
| `Calculator Status` | `/api/v1/calculators/{calculatorId}/status`, `/api/v1/calculators/batch/status` |
| `Analytics` | All `/api/v1/analytics/**` endpoints |
| `Health` | `/api/v1/health` |
