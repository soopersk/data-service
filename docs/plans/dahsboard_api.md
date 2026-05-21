## Endpoint 1: `GET /api/v1/calculators/batch/runs`

### Request
```
GET /api/v1/calculators/batch/runs
  ?reporting_date=2026-05-19          (required, ISO date)
  &frequency=DAILY                    (DAILY|MONTHLY)
  &run_number=1                       (1|2, optional)
  &keys=capitalcalc|portfoliocalc|grportfoliocalc
Header: X-Tenant-Id: tenant1
```

### Response
```json
{
  "reportingDate": "2026-03-06",
  "frequency": "DAILY",
  "runNumber": "1",
  "generatedAt": "2026-03-06T17:00:00Z",
  "calculators": {
    "capital": {
      "calculatorId": "capital",
      "calculatorName": "Capital",
      "runs": [
        {
          "region": "WMAP",
          "runId": "run-wmap-001",
          "status": "SUCCESS",
          "slaStatus": "ON_TIME",
          "startTime": "2026-03-06T13:02:00Z",
          "endTime": "2026-03-06T14:45:00Z",
          "estimatedStartTime": "2026-03-06T13:00:00Z",
          "estimatedEndTime": "2026-03-06T14:50:00Z",
          "sla": "2026-03-06T15:00:00Z",
          "durationMs": 6180000,
          "expectedDurationMs": 5400000,
          "slaBreached": false,
          "isRerun": false
        },
        {
          "region": "LDNL",
          "runId": "run-ldnl-002",
          "status": "FAILED",
          "slaStatus": "LATE",
          "startTime": "2026-03-06T13:02:00Z",
          "endTime": "2026-03-06T14:58:00Z",
          "estimatedStartTime": "2026-03-06T13:00:00Z",
          "estimatedEndTime": "2026-03-06T14:50:00Z",
          "sla": "2026-03-06T15:00:00Z",
          "durationMs": 6960000,
          "expectedDurationMs": 5400000,
          "slaBreached": true,
          "slaBreachReason": "Run status: FAILED",
          "isRerun": true
        }
      ]
    },
    "modelled-exposure": {
      "calculatorId": "modelled-exposure",
      "calculatorName": "Modelled exposure",
      "runs": [
        {
          "runType": "ETD",
          "status": "NOT_STARTED",
          "slaStatus": "IN_PROGRESS",
          "estimatedStartTime": "2026-03-06T17:02:00Z",
          "estimatedEndTime": "2026-03-06T17:58:00Z",
          "sla": "2026-03-06T18:30:00Z",
          "slaBreached": false,
          "isRerun": false
        },
        { "runType": "OTC", "status": "NOT_STARTED", "slaBreached": false, "isRerun": false },
        { "runType": "SFT", "status": "NOT_STARTED", "slaBreached": false, "isRerun": false }
      ]
    },
    "portfolio": {
      "calculatorId": "portfolio",
      "calculatorName": "Portfolio",
      "runs": [
        {
          "status": "NOT_STARTED",
          "slaStatus": "IN_PROGRESS",
          "estimatedStartTime": "2026-03-06T16:02:00Z",
          "estimatedEndTime": "2026-03-06T17:05:00Z",
          "sla": "2026-03-06T17:00:00Z",
          "slaBreached": false,
          "isRerun": false
        }
      ]
    }
  }
}
```

**Notes:**
- `runs` is **empty list** when no run found for a calculatorName — do not omit the key
- `runId` is null for `NOT_STARTED` runs (no DB row yet; derived from config/history estimates)
- `region` and `runType` are mutually exclusive per calculator — a calculator uses one or neither, never both
- `isRerun` = true when that specific dimensional run was re-triggered (UI renders `LDNL*`)
- `slaStatus` is **3-value**: `ON_TIME` | `LATE` | `VERY_LATE`. It is derived from the duration-based
  deadline (`sla` field) using the same band boundary as the on-write grader and live job: LATE if
  overdue ≤ `bandGap` (default 15 min), VERY_LATE beyond. Consistent with `slaBreached`: a run that
  is RUNNING and has `slaBreached=true` will show LATE/VERY_LATE — not ON_TIME.
- `expectedDurationMs` is the originally-estimated run duration (omitted when null/unknown); included
  alongside `durationMs` to support actual-vs-expected comparisons (consistent with `/executions`).