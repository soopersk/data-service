## Endpoint : `GET /api/v1/analytics/calculators/{name}/executions`

Returns each physical run independently — split runs sharing a `correlationId` appear as separate rows, not collapsed. Backed by a new `getRunExecutions()` service method.

**Params:** `calculatorName` (path), `X-Tenant-Id` (header), `days` (default 30, 1–365), `frequency` (default DAILY)


GET /api/v1/analytics/calculators/portfoliocalc/executions
  ?days=30           (1-365, default 30)
  &frequency=DAILY   (default DAILY)
  &run_number=1      (1|2, optional)
Header: X-Tenant-Id: tenant1


### Sample Response
```json
{
  "calculatorId": "portfolio-calc",
  "calculatorName": "portfolio",
  "frequency": "DAILY",
  "periodDays": 30,
  "meanDurationMs": 285000,
  "totalRuns": 30,
  "runningRuns": 1,
  "slaMetCount": 24,
  "lateCount": 4,
  "veryLateCount": 2
  "runs": [
    {
      "runId": "run-2026-05-13-001",
      "reportingDate": "2026-05-13",
      "startTime": "2026-05-13T04:02:15Z",
      "endTime": "2026-05-13T04:07:30Z",
      "durationMs": 315000,
      "expectedDurationMs": 300000,
      "status": "SUCCESS",
      "slaBreached": false,
      "slaStatus": "SLA_MET",
      "runNumber": "1",
      "estimatedStartTime": "2026-05-13T04:00:00Z",
      "slaTime": "2026-05-13T06:30:00Z"
    },
    {
      "runId": "run-2026-05-11-split-1",
      "reportingDate": "2026-05-11",
      "startTime": "2026-05-11T03:59:50Z",
      "endTime": "2026-05-11T04:08:10Z",
      "durationMs": 500000,
      "expectedDurationMs": 300000,
      "status": "SUCCESS",
      "slaBreached": false,
      "slaStatus": "SLA_MET",
      "runNumber": "1",
      "estimatedStartTime": "2026-05-11T04:00:00Z",
      "slaTime": "2026-05-11T06:30:00Z"
    },
    {
      "runId": "run-2026-05-11-split-2",
      "reportingDate": "2026-05-11",
      "startTime": "2026-05-11T04:00:05Z",
      "endTime": "2026-05-11T04:15:45Z",
      "durationMs": 940000,
      "expectedDurationMs": 300000,
      "status": "SUCCESS",
      "slaBreached": true,
      "slaStatus": "VERY_LATE",
      "runNumber": "1",
      "estimatedStartTime": "2026-05-11T04:00:00Z",
      "slaTime": "2026-05-11T06:30:00Z"
    }
  ]
}
```

**Key notes on `expectedDurationMs`:** Sourced from the immutable `expected_duration_ms` column on `calculator_runs` (set at first INSERT, never overwritten). Use this for actual-vs-expected comparison per run. `meanDurationMs` at the envelope level is the 30-day rolling average — use for trend baseline.
