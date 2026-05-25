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
| `slaTime` | Instant | No | UTC. **Optional** — only used as the weakest baseline fallback (no history, no `expectedDurationMs`). The graded deadline is derived from avg runtime |
| `expectedDurationMs` | Long | No | Duration baseline used when history is thin; also drives the estimated-end fallback |
| `estimatedStartTime` | Instant | No | UTC. If omitted, derived from the calculator's historical avg start, else falls back to `startTime` |
| `estimatedEndTime` | Instant | No | UTC. If omitted, derived from `start + expectedDurationMs`, else `estimatedStart + avg duration` |
| `runNumber` / `runType` / `region` | String | No | Promoted dimensional fields (also accepted inside `runParameters`) |
| `correlationId` | String | No | Marks physical splits of one logical run |
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

### `GET /api/v1/calculators/batch/runs`

Dimensional run state for a set of calculators on a specific reporting date. Powers the multi-section dashboard. Unlike `/batch/status`, calculators are identified by their **readable `calculator_name`** (unique per tenant) — not the upstream UUID `calculator_id`. UUIDs never appear in the request or response.

**Query Parameters:**

| Name | Type | Default | Constraints |
|------|------|---------|-------------|
| `reporting_date` | Date | Required | `YYYY-MM-DD` |
| `frequency` | String | `DAILY` | `DAILY` or `MONTHLY` |
| `run_number` | String | none | `1` or `2` when provided (omit for all buckets) |
| `keys` | String | Required | Pipe-separated `calculator_name` values, e.g. `capitalcalc|portfoliocalc|grportfoliocalc` |

**Response `200 OK`**, `Cache-Control: max-age=30, private`:

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
          "expectedDurationMs": 5400000,
          "slaBreached": false,
          "isRerun": false
        },
        {
          "runId": "run-ldnl-002",
          "region": "LDNL",
          "status": "FAILED",
          "slaStatus": "VERY_LATE",
          "startTime": "2026-05-19T13:02:00Z",
          "endTime": "2026-05-19T15:18:00Z",
          "estimatedStartTime": "2026-05-19T13:00:00Z",
          "estimatedEndTime": "2026-05-19T14:50:00Z",
          "sla": "2026-05-19T15:00:00Z",
          "durationMs": 8160000,
          "expectedDurationMs": 5400000,
          "slaBreached": true,
          "slaBreachReason": "Run status: FAILED",
          "isRerun": true
        }
      ]
    },
    "modelledexposurecalc": {
      "calculatorName": "modelledexposurecalc",
      "runs": [
        {
          "runId": "run-otc-001",
          "runType": "OTC",
          "status": "SUCCESS",
          "slaStatus": "ON_TIME",
          "startTime": "2026-05-19T17:03:00Z",
          "endTime": "2026-05-19T17:49:00Z",
          "estimatedStartTime": "2026-05-19T17:02:00Z",
          "estimatedEndTime": "2026-05-19T17:58:00Z",
          "sla": "2026-05-19T18:30:00Z",
          "durationMs": 2760000,
          "expectedDurationMs": 3360000,
          "slaBreached": false,
          "isRerun": false
        },
        {
          "runId": "run-sft-001",
          "runType": "SFT",
          "status": "RUNNING",
          "slaStatus": "ON_TIME",
          "startTime": "2026-05-19T17:05:00Z",
          "estimatedStartTime": "2026-05-19T17:02:00Z",
          "estimatedEndTime": "2026-05-19T17:58:00Z",
          "sla": "2026-05-19T18:30:00Z",
          "expectedDurationMs": 3360000,
          "slaBreached": false,
          "isRerun": false
        }
      ]
    },
    "portfoliocalc": {
      "calculatorName": "portfoliocalc",
      "runs": [
        {
          "slaStatus": "ON_TIME",
          "estimatedStartTime": "2026-05-19T16:02:00Z",
          "estimatedEndTime": "2026-05-19T17:05:00Z",
          "expectedDurationMs": 3780000,
          "slaBreached": false,
          "isRerun": false
        }
      ]
    },
    "newcalc": { "calculatorName": "newcalc", "runs": [] }
  }
}
```

The sample shows every entry shape a consumer must handle: **regional actual runs** (`capitalcalc` — `WMAP` completed on-time, `LDNL` failed and breached; note `status` and `slaStatus` are independent), **typed actual runs** (`modelledexposurecalc` — `OTC` completed, `SFT` still RUNNING with no `endTime`/`durationMs`), a **synthetic NOT_STARTED entry** (`portfoliocalc` — no `runId`/`status`, only estimates), and a **brand-new calculator with no history** (`newcalc` — empty `runs`).

**Notes:**
- The response `calculators` map is keyed by `calculator_name`. `CalculatorEntry` exposes only `calculatorName` — there is no `calculatorId` field.
- Every requested key is always present in `calculators` (never omitted), so consumers can iterate the request list directly.
- **`runs` content per calculator:**
  - **Has actual runs** → one `RunEntry` per dimension (`region` or `runType`) with `runId` + `status` populated.
  - **No run on the date but has history/profile** → a single **synthetic NOT_STARTED entry**: `runId` and `status` are absent, `slaStatus` is `ON_TIME`, and `estimatedStartTime` / `estimatedEndTime` / `expectedDurationMs` are projected from the rolling profile (or the most recent run's stored estimates). **Detect "not started" by the absence of `runId`/`status`, not by an empty list.**
  - **Brand-new calculator with zero history** → `runs` is an empty list `[]`.
- `status` (run lifecycle: `RUNNING`/`SUCCESS`/`FAILED`/`TIMEOUT`/`CANCELLED`) and `slaStatus` (SLA classification) are **independent**. `slaStatus` is always one of `ON_TIME` / `LATE` / `VERY_LATE` — never a lifecycle value like `FAILED`. A `FAILED` run that breached its deadline reports `slaStatus: "LATE"` or `"VERY_LATE"`.
- `slaStatus` is duration-based and consistent with `slaBreached`: `ON_TIME` only when neither flagged breached nor past the derived deadline; `LATE` within one band-gap (default 15 min) past `sla`, `VERY_LATE` beyond. A `RUNNING` run already flagged `slaBreached=true` reports `LATE`/`VERY_LATE`, not `ON_TIME`.
- `region` and `runType` are mutually exclusive per calculator (one or neither, never both).
- `isRerun: true` means a re-trigger occurred for that specific dimensional run; UI typically renders the dimension label with a `*` suffix.
- `estimatedStartTime` / `estimatedEndTime` / `sla` are the run's stored values, set at start by precedence **request → cached profile (avg start / avg duration) → computed**. `sla` is the duration-derived deadline, not an upstream wall-clock time.
- `expectedDurationMs` is the originally-configured duration baseline (immutable after first INSERT); compare against actual `durationMs`.
- Null run-entry fields are omitted from JSON (`@JsonInclude(NON_NULL)`).

---

## Analytics

All analytics endpoints:
- Require `Authorization` + `X-Tenant-Id`
- Return `Cache-Control: max-age=60, private` (except SLA breaches: `no-cache`)
- Are backed by a 5-minute Redis analytics cache
- `run-performance` keys are evicted on run start; all analytics keys are evicted on run completion/breach

### `GET /api/v1/analytics/calculators/{calculatorId}/runtime`

Average runtime statistics over a lookback window.

**Query Parameters:** `days` (required, 1-365), `frequency` (default `DAILY`)

**Data source:** `calculator_sli_daily`

**Response:**
```json
{
  "calculatorId": "calc-1",
  "periodDays": 30,
  "frequency": "DAILY",
  "avgDurationMs": 8100000,
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

---

### `GET /api/v1/analytics/calculators/{calculatorId}/trends`

Per-day trend data with run counts, durations, and SLA status.

---

### `GET /api/v1/analytics/calculators/{calculatorId}/sla-breaches`

Paginated detailed breach event log. Always returns fresh data (`no-cache`).

---

### `GET /api/v1/analytics/calculators/{calculatorId}/run-performance`

Raw run-level performance data.

**Query Parameters:** `days` (default `30`, 1-365), `frequency` (default `DAILY`)

**Data source:** `calculator_runs` LEFT JOIN `sla_breach_events`

**Response:**
```json
{
  "calculatorId": "calc-1",
  "calculatorName": "My Calculator",
  "frequency": "DAILY",
  "periodDays": 30,
  "meanDurationMs": 8100000,
  "totalRuns": 28,
  "runningRuns": 1,
  "slaMetCount": 24,
  "lateCount": 3,
  "veryLateCount": 1,
  "runs": [
    {
      "runId": "run-abc-123",
      "reportingDate": "2026-01-24",
      "startTime": "2026-01-24T09:00:00Z",
      "endTime": "2026-01-24T11:15:00Z",
      "durationMs": 7980000,
      "status": "SUCCESS",
      "slaStatus": "ON_TIME",
      "slaBreached": false
    },
    {
      "runId": "run-abc-124",
      "reportingDate": "2026-01-25",
      "startTime": "2026-01-25T09:00:00Z",
      "endTime": null,
      "durationMs": null,
      "status": "RUNNING",
      "slaStatus": "ON_TIME",
      "slaBreached": false
    }
  ],
  "estimatedStartTime": "2026-01-24T09:00:00Z",
  "slaTime": "2026-01-24T11:00:00Z"
}
```

Run-level SLA status values: `ON_TIME`, `LATE`, `VERY_LATE`. A RUNNING run (or any not-yet-breached run) reports `ON_TIME` — `slaStatus` is never a lifecycle value like `RUNNING`.

Counters: `totalRuns` is terminal/evaluated only; `runningRuns` is tracked separately.

---

### `GET /api/v1/analytics/calculators/{calculatorName}/executions`

Raw run-by-run execution history. Distinct from `/run-performance` in two ways:
1. The path variable is the **readable `calculator_name`**, not the upstream UUID `calculator_id`.
2. Split runs (rows sharing a `correlation_id`) are **not collapsed** — each physical row appears as its own entry. `subRunIds` is always `null`. For the grouped/logical view, use `/run-performance`.

**Query Parameters:** `days` (default `30`, 1-365), `frequency` (default `DAILY`), `run_number` (`1` or `2`, optional)

**Data source:** `calculator_runs` LEFT JOIN `sla_breach_events` (filtered by `calculator_name`)

**Response `200 OK`**, `Cache-Control: max-age=60, private`:

Shape is `RunPerformanceData` (the same record `/run-performance` returns). The envelope's `calculatorId` and `calculatorName` fields both carry the readable name — no UUID leak, because the lookup is by name.

```json
{
  "calculatorId": "portfoliocalc",
  "calculatorName": "portfoliocalc",
  "frequency": "DAILY",
  "periodDays": 30,
  "meanDurationMs": 285000,
  "totalRuns": 29,
  "runningRuns": 1,
  "slaMetCount": 24,
  "lateCount": 3,
  "veryLateCount": 2,
  "runs": [
    {
      "runId": "run-2026-05-13-001",
      "reportingDate": "2026-05-13",
      "startTime": "2026-05-13T04:02:15Z",
      "endTime": "2026-05-13T04:07:30Z",
      "durationMs": 315000,
      "status": "SUCCESS",
      "slaBreached": false,
      "slaStatus": "ON_TIME",
      "subRunIds": null,
      "estimatedStartTime": "2026-05-13T04:00:00Z",
      "slaTime": "2026-05-13T06:30:00Z",
      "runNumber": "1",
      "expectedDurationMs": 300000
    },
    {
      "runId": "run-2026-05-14-001",
      "reportingDate": "2026-05-14",
      "startTime": "2026-05-14T04:01:00Z",
      "endTime": null,
      "durationMs": null,
      "status": "RUNNING",
      "slaBreached": false,
      "slaStatus": "ON_TIME",
      "subRunIds": null,
      "estimatedStartTime": "2026-05-14T04:00:00Z",
      "slaTime": "2026-05-14T06:30:00Z",
      "runNumber": "1",
      "expectedDurationMs": 300000
    }
  ],
  "estimatedStartTime": "2026-05-13T04:00:00Z",
  "slaTime": "2026-05-13T06:30:00Z"
}
```

**Envelope fields:**

| Field | Type | Notes |
|-------|------|-------|
| `calculatorId` / `calculatorName` | String | Both carry the readable `calculator_name` |
| `frequency` | String | Echo of the request |
| `periodDays` | int | Echo of `days` |
| `meanDurationMs` | long | Mean over **completed** runs only (duration > 0); `0` when none |
| `totalRuns` | int | Terminal/evaluated runs only (excludes RUNNING) |
| `runningRuns` | int | Currently-running runs |
| `slaMetCount` / `lateCount` / `veryLateCount` | int | Per-`slaStatus` tallies over terminal runs |
| `runs` | array | One entry per physical row (see below) |
| `estimatedStartTime` / `slaTime` | Instant | Chart reference lines — see note below |

**Per-run fields (`RunDataPoint`):**

| Field | Type | Notes |
|-------|------|-------|
| `runId` | String | Physical run id |
| `reportingDate` | Date | `YYYY-MM-DD` |
| `startTime` | Instant | UTC |
| `endTime` | Instant | `null` while RUNNING |
| `durationMs` | Long | `null` while RUNNING |
| `status` | String | `RUNNING`/`SUCCESS`/`FAILED`/`TIMEOUT`/`CANCELLED` |
| `slaBreached` | Boolean | Persisted breach flag |
| `slaStatus` | String | `ON_TIME` / `LATE` / `VERY_LATE` (never a lifecycle value) |
| `subRunIds` | array | Always `null` on this endpoint (no split-grouping) |
| `estimatedStartTime` | Instant | The run's own stored estimate |
| `slaTime` | Instant | The run's own derived deadline |
| `runNumber` | String | Run bucket (`1` / `2`) |
| `expectedDurationMs` | Long | Configured baseline, immutable; compare to `durationMs` |

> Unlike `/batch/runs`, `RunPerformanceData` is **not** `@JsonInclude(NON_NULL)` — `null` fields (e.g. `endTime`, `durationMs`, `subRunIds` of a RUNNING run) are present in the JSON as `null`.

**Empty result:** when no run matches the window, the endpoint returns `200 OK` (not `404`) with `calculatorName: null`, `runs: []`, and all counters `0`.

**Envelope reference lines (`estimatedStartTime`, `slaTime`):** the two top-level fields are chart reference lines sourced from the calculator's cached **profile** — the typical (historical-average) start and a typical buffered deadline (`avgDuration × (1 + thresholdPercent) + lateBand`) — so they stay stable across the window. When the calculator has fewer than `min-sample-size` runs, they fall back to the most recent run's stored values. Per-run rows are live from `calculator_runs`; the envelope reference lines come from the profile cache (warmed nightly).

**Example:**
```bash
curl -u admin:admin -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/analytics/calculators/portfoliocalc/executions?days=30"
```

---

---

## Analytics Projections

All projection endpoints:
- Require `Authorization` + `X-Tenant-Id`
- Are served by `ProjectionController` → focused `*Projection` services
- Cache is read before calling the domain service; response is written back with a smart TTL

### `GET /api/v1/analytics/projections/calculators/{calculatorId}/performance-card`

Pre-formatted projection payload for dashboard consumers (CET labels, chart coordinates, percentages).

**Query Parameters:** `days` (default `30`, 1-365), `frequency` (default `DAILY`)

**Composition:** `PerformanceCardProjection` → `AnalyticsService.getRunPerformanceData()`

**Response `PerformanceCardResponse`:** Includes `calculatorId`, `calculatorName`, `meanDurationFormatted`, `schedule` (frequency + estimated CET start), `slaSummary` (sla-met/late/very-late percentages), `runs` (list of `RunBar` with CET start/end labels, duration formatted), and `referenceLines` (SLA marker positions).

---

### `GET /api/v1/analytics/projections/calculator-dashboard`

Unified multi-section dashboard for the Capital Calculation Insight UI.

**Query Parameters:**

| Name | Type | Default | Constraints |
|------|------|---------|-------------|
| `reportingDate` | Date | Required | `YYYY-MM-DD` |
| `frequency` | String | `DAILY` | `DAILY` or `MONTHLY` |
| `runNumber` | int | `1` | Min 1, Max 2 |

**Cache:** `obs:analytics:dashboard:status:{tenantId}:{reportingDate}:{frequency}:{runNumber}` — smart TTL (same tiers as regional batch cache).

**Composition:** `DashboardProjection` → `DashboardService.buildDashboard()`

**Response `CalculatorDashboardResponse`:**
```json
{
  "reportingDate": "2026-04-17",
  "reportingDateFormatted": "Thu 17 Apr 2026",
  "frequency": "DAILY",
  "runNumber": 1,
  "sections": [
    {
      "sectionKey": "REGIONAL",
      "displayName": "Regional",
      "displayOrder": 1,
      "sla": { "deadline": "17:45", "breached": false },
      "dependency": null,
      "summary": { "status": "ON_TIME", "completedCount": 8, "totalCount": 10 },
      "calculators": [ ... ],
      "displayLabels": null
    },
    {
      "sectionKey": "PORTFOLIO",
      "displayName": "Portfolio",
      "displayOrder": 2,
      "sla": { "deadline": "18:30", "breached": false },
      "dependency": { "sectionKey": "REGIONAL", "satisfied": true },
      "summary": { "status": "ON_TIME", "completedCount": 1, "totalCount": 1 },
      "calculators": [
        {
          "calculatorId": "portfolio-cap-calc",
          "displayName": "Portfolio CAP",
          "status": "ON_TIME",
          "startTimeCet": "17:52 CET",
          "endTimeCet": "18:10 CET",
          "durationFormatted": "18mins 0s",
          "hasSubRuns": false,
          "subRuns": [],
          "history": [ { "reportingDate": "2026-04-16", "status": "ON_TIME", "slaBreached": false } ]
        }
      ],
      "displayLabels": ["CAP - AM", "CAP - CM", "CAP - PM", "CAP - EOD", "CAP - Final"]
    }
  ]
}
```

**`displayLabels`:** Non-null only for sections where multiple UI rows are rendered from a single calculator run (e.g. Portfolio's 5 CAP rows). `null` for all other sections.

**Status values:** `ON_TIME`, `DELAYED`, `FAILED`, `RUNNING`, `NOT_STARTED`

---

### `GET /api/v1/analytics/projections/regional-batch-status` ⚠️ Deprecated

**Deprecation notice:** This endpoint is superseded by `/calculator-dashboard`, which returns a richer superset including all calculator sections. Migrate before **2026-05-31**.

Response headers on every call:
- `Deprecation: true`
- `Sunset: Sun, 31 May 2026 00:00:00 GMT`
- `Link: </api/v1/analytics/projections/calculator-dashboard>; rel="successor-version"`

**Query Parameters:** `reportingDate` (Date, required)

**Cache:** `obs:analytics:regional-batch:status:{tenantId}:{reportingDate}` — smart TTL (see §5.4 in tech-spec).

**Composition:** `RegionalBatchProjection` → `RegionalBatchService.getRegionalBatchStatus()`

**Response `RegionalBatchStatusResponse`:** Includes overall SLA state, estimated start/end times (`TimeReference` with CET time, hourCet, actual/estimated flag), per-region status rows (`ON_TIME` / `DELAYED` / `FAILED` / `RUNNING` / `NOT_STARTED`), and run tooltip data (start/end CET, duration ms, batch type).

**Status classification:** TIMEOUT and CANCELLED terminal runs are treated as `FAILED` (same as explicit FAILED status).

---

## OpenAPI Tags

| Tag | Endpoints |
|-----|-----------|
| `Run Ingestion` | `/api/v1/runs/start`, `/api/v1/runs/{runId}/complete` |
| `Calculator Status` | `/api/v1/calculators/{calculatorId}/status`, `/api/v1/calculators/batch/status`, `/api/v1/calculators/batch/runs` |
| `Analytics` | All `/api/v1/analytics/**` endpoints |
| `Analytics Projections` | `/api/v1/analytics/projections/**` endpoints |
| `Health` | `/api/v1/health` |



