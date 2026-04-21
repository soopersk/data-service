# Performance Card Projection API Specification

## 1. Endpoint Summary

- **Method**: `GET`
- **Path**: `/api/v1/analytics/projections/calculators/{calculatorId}/performance-card`
- **Purpose**: Returns a UI-ready performance card for one calculator (pre-formatted date/time labels, chart coordinates, SLA summary percentages, duration strings).
- **Controller**: `ProjectionController#getPerformanceCard`

## 2. Request Contract

### 2.1 Path Params

- `calculatorId` (`string`, required)

### 2.2 Headers

- `X-Tenant-Id` (`string`, required)
  - Missing header returns HTTP `400`.

### 2.3 Query Params

- `days` (`integer`, optional, default `30`, min `1`, max `365`)
- `frequency` (`string`, optional, default `DAILY`)
  - Accepted values (case-insensitive): `DAILY`, `D`, `MONTHLY`, `M`
  - Any other value returns HTTP `400`.

### 2.4 Example Request

```http
GET /api/v1/analytics/projections/calculators/calc-pricing-engine-001/performance-card?days=30&frequency=DAILY
X-Tenant-Id: tenant-a
```

## 3. Response Contract (HTTP 200)

### 3.1 Headers

- `Cache-Control: private, max-age=60`

### 3.2 JSON Schema (practical contract)

```json
{
  "calculatorId": "string",
  "calculatorName": "string|null",
  "schedule": {
    "estimatedStartTimeCet": "HH:mm|null",
    "frequency": "DAILY|MONTHLY"
  },
  "periodDays": 30,
  "meanDurationMs": 5940000,
  "meanDurationFormatted": "string|null",
  "slaSummary": {
    "totalRuns": 30,
    "slaMetCount": 21,
    "slaMetPct": 70.0,
    "lateCount": 5,
    "latePct": 16.7,
    "veryLateCount": 4,
    "veryLatePct": 13.3
  },
  "runs": [
    {
      "runId": "string",
      "reportingDate": "YYYY-MM-DD|null",
      "dateFormatted": "EEE dd MMM yyyy|null",
      "startHourCet": 10.03,
      "endHourCet": 11.41,
      "startTimeCet": "HH:mm CET|null",
      "endTimeCet": "HH:mm CET|null",
      "durationMs": 5880000,
      "durationFormatted": "string|null",
      "slaStatus": "SLA_MET|LATE|VERY_LATE|RUNNING"
    }
  ],
  "referenceLines": {
    "slaStartHourCet": 10.00,
    "slaEndHourCet": 12.00
  }
}
```

## 4. Field Semantics and Formatting Rules

### 4.1 Top-level

- `periodDays`: echoes effective lookback used for query.
- `meanDurationMs`: arithmetic mean of non-running runs with positive duration.
- `meanDurationFormatted`: generated from `meanDurationMs`:
  - `Xhrs Ymins` if hours > 0
  - `Xmins Ys` if < 1 hour and minutes > 0
  - `Xs` otherwise

### 4.2 `schedule`

- `estimatedStartTimeCet`: derived from calculator estimated start, formatted `HH:mm`.
- `frequency`: normalized enum (`DAILY` or `MONTHLY`).

### 4.3 `slaSummary`

- Percentages are computed against `totalRuns` (terminal runs only, excludes running runs).
- Formula: `round((count / totalRuns) * 100, 1 decimal)`.
- If `totalRuns == 0`, all percentages are `0.0`.

### 4.4 `runs[]`

- Ordering: ascending by `reportingDate`, then creation time (oldest to newest).
- `startHourCet` / `endHourCet`: decimal hour in Amsterdam timezone (`Europe/Amsterdam`) rounded to 2 decimals.
  - Example: `10.03` means ~10:02-10:03 depending on rounding.
- `startTimeCet` / `endTimeCet`: formatted label for UI display (`HH:mm CET`).
- `durationMs`: if source duration is `null`, API returns `0`.
- `durationFormatted`: can still be `null` if source duration is `null`.
- `slaStatus` values:
  - `RUNNING`: run status is running.
  - `SLA_MET`: not breached.
  - `LATE`: breached with low/medium/unknown severity.
  - `VERY_LATE`: breached with high/critical severity.

### 4.5 `referenceLines`

- `slaStartHourCet`: decimal hour reference for expected start.
- `slaEndHourCet`: decimal hour reference for SLA deadline.
- Both can be `null` if schedule config is absent.

## 5. Empty-state Contract

If no runs exist for the selection:

- `calculatorId`: returned
- `calculatorName`: `null`
- `schedule`: `null`
- `periodDays`: returned
- `meanDurationMs`: `0`
- `meanDurationFormatted`: `null`
- `slaSummary`: all zeroes
- `runs`: `[]`
- `referenceLines`: `null`

## 6. Error Contract

Global error envelope:

```json
{
  "timestamp": "2026-04-20T14:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Frequency is required. Valid values: DAILY, D, MONTHLY, M"
}
```

Possible statuses for this endpoint:

- `400` invalid query param value/range, invalid frequency, missing `X-Tenant-Id`
- `403` tenant/security access denied
- `404` calculator/domain entity not found (if thrown by service layer)
- `500` unexpected server error

## 7. UI Integration Notes

- Treat backend-provided strings (`dateFormatted`, `startTimeCet`, `endTimeCet`, `meanDurationFormatted`, `durationFormatted`) as display-ready and do not reformat them.
- Use decimal hour fields for chart plotting, and time strings for tooltips/labels.
- Handle nullable fields safely (especially empty state and running runs).
- Keep query `days` in `[1, 365]`; cap UI controls accordingly.
- Use polling/refresh strategy aligned with `max-age=60`.

## 8. Known Data Nuance

- Time conversion uses `Europe/Amsterdam` timezone (DST-aware), even though response labels use `CET`.
- Sample payload file `perf_card_sample_respnose.json` is representative, but percentage precision in live responses is one decimal place.

