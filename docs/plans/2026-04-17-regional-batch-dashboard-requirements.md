# Regional Batch Dashboard — Business Requirements

**Date:** 2026-04-17
**Status:** Implemented
**Endpoint:** `GET /api/v1/analytics/projections/regional-batch-status`

---

## 1. Business Context

Capital calculations (CAP-4, CAP-5, CAP-6, etc.) depend on 10 regional batch runs that execute in a defined order. Operations teams need a single view showing the real-time status of each regional batch for a given business date, whether the overall SLA was met, and which regions caused delays.

This endpoint powers the **Regional Section** of the Calculator Dashboard UI, where each CAP calculator row displays 10 colored status buttons — one per region.

---

## 2. Regional Batch Model

### Regions (Run Order)

| # | Region | Description |
|---|--------|-------------|
| 1 | WMAP | |
| 2 | WMDE | |
| 3 | ASIA | |
| 4 | WMUS | |
| 5 | AUNZ | |
| 6 | WMCH | |
| 7 | ZURI | |
| 8 | LDNL | |
| 9 | AMER | |
| 10 | EURO | |

### Key Properties

- Each regional batch is a **separate calculator run** with its own `calculator_id`.
- Regional batches are **shared across all CAP calculators** — every CAP row displays the same 10 buttons.
- Region identity is stored in `run_parameters` as `{"run_type": "BATCH", "region": "AMER", ...}`.
- If multiple runs exist for the same region on the same reporting date, **pick the latest by `start_time`**.

---

## 3. Overall SLA

A single SLA deadline (CET time-of-day, e.g., 17:45) defines when **all** regional batches must complete. This is configured server-side, not per-run.

- **Breached** = any completed region's `end_time` exceeds the SLA deadline.
- The response includes a list of regions that caused the breach.

---

## 4. Status Definitions

Each regional batch button has one of five statuses:

| Status | Color | Condition |
|--------|-------|-----------|
| **ON_TIME** | Green | Completed before the overall SLA deadline |
| **DELAYED** | Amber | Completed but after the overall SLA deadline |
| **FAILED** | Red | Run ended with FAILED status |
| **RUNNING** | Blue/Grey | Run is currently in progress |
| **NOT_STARTED** | Empty/Grey | No run found for this region on the reporting date |

---

## 5. Estimated Start / End

Predicted values based on the **median** of the last 7 days of historical data, with **actual override** once today's runs begin.

### Estimation Method (Median)

| Field | Derivation |
|-------|------------|
| **Estimated Start** | For each of the last 7 reporting dates, find the earliest regional batch start time (CET seconds-of-day). Take the **median** across those 7 daily values. `basedOn` = the region that was earliest most frequently. |
| **Estimated End** | For each of the last 7 reporting dates, find the latest regional batch end time (CET seconds-of-day) among completed runs. Take the **median**. `basedOn` = the region that was latest most frequently. |

Median is used instead of mean because it is robust to outliers — a single abnormally delayed day does not distort the prediction.

### Actual Override

| Condition | Behavior |
|-----------|----------|
| At least one region has started today | `estimatedStart` switches to the **actual** earliest start time. `actual = true`. |
| All regions have completed (no RUNNING or NOT_STARTED) | `estimatedEnd` switches to the **actual** latest end time. `actual = true`. |
| No runs yet / some still running | `estimatedEnd` remains the median prediction. `actual = false`. |

The `actual` boolean on the `TimeReference` object tells the UI whether the value is a prediction or observed fact.

---

## 6. Tooltip (per button)

Each regional status button exposes a tooltip with:

| Field | Description |
|-------|-------------|
| Start Time | Run start time in CET (e.g., "04:15 CET") |
| End Time | Run end time in CET (null if RUNNING) |
| Duration | Formatted duration (e.g., "2hrs 15mins") |
| Batch Type | From `run_parameters.run_type` (typically "BATCH") |
| Run Day | Derived from run's `start_time` converted to CET date (e.g., "Fri 17 Apr 2026") |

---

## 7. Request / Response Contract

### Request

```
GET /api/v1/analytics/projections/regional-batch-status?reportingDate=2026-04-17
Header: X-Tenant-Id: <tenant>
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `reportingDate` | `LocalDate` (ISO) | Yes | Business date to query |
| `X-Tenant-Id` | Header | Yes | Tenant identifier |

### Response

```json
{
  "reportingDate": "2026-04-17",
  "reportingDateFormatted": "Fri 17 Apr 2026",
  "overallSla": {
    "deadlineTimeCet": "17:45",
    "breached": true
  },
  "estimatedStart": {
    "timeCet": "04:15 CET",
    "hourCet": 4.25,
    "basedOn": "ASIA",
    "actual": true
  },
  "estimatedEnd": {
    "timeCet": "17:30 CET",
    "hourCet": 17.50,
    "basedOn": "EURO",
    "actual": false
  },
  "totalRegions": 10,
  "completedRegions": 8,
  "runningRegions": 1,
  "failedRegions": 1,
  "regions": [
    {
      "region": "WMAP",
      "runId": "run-wmap-20260417",
      "status": "ON_TIME",
      "startTimeCet": "05:00 CET",
      "endTimeCet": "07:15 CET",
      "startHourCet": 5.00,
      "endHourCet": 7.25,
      "durationMs": 8100000,
      "durationFormatted": "2hrs 15mins",
      "runDay": "Fri 17 Apr 2026",
      "batchType": "BATCH",
      "slaBreached": false
    },
    {
      "region": "WMDE",
      "runId": "run-wmde-20260417",
      "status": "DELAYED",
      "startTimeCet": "06:30 CET",
      "endTimeCet": "18:10 CET",
      "startHourCet": 6.50,
      "endHourCet": 18.17,
      "durationMs": 42000000,
      "durationFormatted": "11hrs 40mins",
      "runDay": "Fri 17 Apr 2026",
      "batchType": "BATCH",
      "slaBreached": true
    },
    {
      "region": "AUNZ",
      "runId": "run-aunz-20260417",
      "status": "RUNNING",
      "startTimeCet": "10:00 CET",
      "endTimeCet": null,
      "startHourCet": 10.00,
      "endHourCet": null,
      "durationMs": null,
      "durationFormatted": null,
      "runDay": "Fri 17 Apr 2026",
      "batchType": "BATCH",
      "slaBreached": false
    },
    {
      "region": "WMCH",
      "runId": "run-wmch-20260417",
      "status": "FAILED",
      "startTimeCet": "08:00 CET",
      "endTimeCet": "08:45 CET",
      "startHourCet": 8.00,
      "endHourCet": 8.75,
      "durationMs": 2700000,
      "durationFormatted": "45mins 0s",
      "runDay": "Fri 17 Apr 2026",
      "batchType": "BATCH",
      "slaBreached": false
    },
    {
      "region": "WMUS",
      "runId": null,
      "status": "NOT_STARTED",
      "startTimeCet": null,
      "endTimeCet": null,
      "startHourCet": null,
      "endHourCet": null,
      "durationMs": null,
      "durationFormatted": null,
      "runDay": null,
      "batchType": null,
      "slaBreached": false
    }
  ],
  "slaBreachedRegions": ["WMDE"]
}
```

### Nullability Rules

| Status | Nullable Fields |
|--------|----------------|
| ON_TIME / DELAYED | None — all fields populated |
| FAILED | None — all fields populated |
| RUNNING | `endTimeCet`, `endHourCet`, `durationMs`, `durationFormatted` |
| NOT_STARTED | All fields except `region`, `status`, `slaBreached` |

---

## 8. Configuration

```yaml
observability:
  regional-batch:
    overall-sla-time-cet: "17:45"
    region-order: [WMAP, WMDE, ASIA, WMUS, AUNZ, WMCH, ZURI, LDNL, AMER, EURO]
```

Both values are server-side configuration. Region order controls the display sequence of buttons in the response. The SLA time is in CET (Europe/Amsterdam, DST-aware).

---

## 9. Data Source

### Current-day query
- **Table:** `calculator_runs` (range-partitioned by `reporting_date`)
- **Filter:** `run_parameters->>'run_type' = 'BATCH'` with non-null `region`
- **Dedup:** `ROW_NUMBER() OVER (PARTITION BY region ORDER BY start_time DESC)` — latest run per region
- **Performance:** Exact `reporting_date` filter ensures single-partition scan. No additional index needed.

### Historical query (for median estimation)
- **Range:** Last 7 days excluding the requested `reportingDate`
- **Filter:** Same JSONB filter, excludes `RUNNING` status (only completed runs)
- **Returns:** Lightweight `(region, reporting_date, start_time, end_time)` tuples — no full `CalculatorRun` objects
- **Performance:** Scans ~7 partitions. Acceptable for a dashboard endpoint with low QPS.

---

## 10. Server-Side Caching

The dashboard polls every 60 seconds. Without caching both DB queries fire on every request.
Two properties of the data allow aggressive caching:

- **History is immutable for a date.** `findRegionalBatchHistory` queries the 7 days *before*
  the requested date — already finalized data. The median estimation never changes for a given
  `(tenantId, reportingDate)`.
- **The full result is immutable once all regions are terminal.** Once every region is ON_TIME,
  DELAYED, or FAILED (none RUNNING, none NOT_STARTED), no further DB change can alter the result.

### Two-tier cache design

#### Tier 1 — History Cache (`RegionalBatchCacheService.getHistory / putHistory`)

| Property | Value |
|----------|-------|
| Redis key | `obs:analytics:regional-batch:history:{tenantId}:{reportingDate}` |
| TTL | 24 hours |
| Written | On first cache miss per `(tenantId, reportingDate)` |
| Eliminates | `findRegionalBatchHistory` (7-partition scan) on every request after first |

#### Tier 2 — Status Response Cache (`RegionalBatchCacheService.getStatusResponse / putStatusResponse`)

| Property | Value |
|----------|-------|
| Redis key | `obs:analytics:regional-batch:status:{tenantId}:{reportingDate}` |
| TTL | Smart (see below) |
| Written | After every full computation in `ProjectionService` |
| Eliminates | Both DB queries on cache hit |

#### Smart TTL rules

| State | Condition | TTL | Rationale |
|-------|-----------|-----|-----------|
| TERMINAL_CLEAN | 0 running, 0 not-started, 0 failed | **4 hours** | Fully complete, no re-runs expected |
| TERMINAL_WITH_FAILURES | 0 running, 0 not-started, ≥1 failed | **5 minutes** | A failed region may be re-run |
| ACTIVE | ≥1 running | **30 seconds** | State changes frequently |
| NOT_STARTED | 0 runs found yet | **60 seconds** | Nothing expected to change immediately |

#### Request / cache flow

```
GET /regional-batch-status?reportingDate=YYYY-MM-DD

1. Check STATUS cache  →  HIT: return immediately (0 DB queries)
                       ↓ MISS
2. findRegionalBatchRuns()  →  DB (single-partition, always fresh)

3. Check HISTORY cache →  HIT: use cached history
                       ↓ MISS
4. findRegionalBatchHistory()  →  DB (7-partition scan)
   → Store in HISTORY cache (24h TTL)

5. Compute RegionalBatchResult

6. Determine TTL from state → Store in STATUS cache

7. Format and return RegionalBatchStatusResponse
```

On a typical day:
- **First request**: 2 DB queries (same as without cache)
- **Subsequent requests while batches running**: 1 DB query (history cached)
- **After all batches complete**: 0 DB queries for ~4 hours

### Implementation

- `RegionalBatchCacheService` — new service, two pairs of `get/put` methods, all Redis
  exceptions swallowed with `log.warn` (cache is best-effort, never blocks the response)
- `RegionalBatchService.loadHistory()` — checks history cache before calling
  `findRegionalBatchHistory`; stores result on miss
- `ProjectionService.getRegionalBatchStatus()` — checks status cache before calling
  `RegionalBatchService`; stores response with smart TTL on miss
- History is loaded **at most once per request**: a single boolean check gates the
  `loadHistory()` call before both estimation branches run
