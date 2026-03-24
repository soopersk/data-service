# Quick Start

This guide walks through a complete run lifecycle end-to-end: starting a run, completing it, and querying its status.

---

## Prerequisites

- Service running locally (see [Local Setup](local-setup.md))
- `curl` or an HTTP client such as Postman/Insomnia

---

## Step 1: Start a Calculator Run

Notify the service that calculator `calc-daily-fx` has started its run for the reporting date `2026-03-24`.

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/v1/runs/start \
  -d '{
    "runId": "run-fx-20260324-001",
    "calculatorId": "calc-daily-fx",
    "calculatorName": "FX Rate Calculator",
    "frequency": "DAILY",
    "reportingDate": "2026-03-24",
    "startTime": "2026-03-24T05:00:00Z",
    "slaTimeCet": "06:30:00",
    "expectedDurationMs": 3600000
  }'
```

**Expected response — `201 Created`:**

```json
{
  "runId": "run-fx-20260324-001",
  "calculatorId": "calc-daily-fx",
  "calculatorName": "FX Rate Calculator",
  "status": "RUNNING",
  "startTime": "2026-03-24T05:00:00Z",
  "endTime": null,
  "durationMs": null,
  "slaBreached": false,
  "slaBreachReason": null
}
```

The `Location` response header points to `/api/v1/runs/run-fx-20260324-001`.

!!! tip "SLA Deadline"
    `slaTimeCet: "06:30:00"` means the run must complete by 06:30 CET on the reporting date. The service converts this to a UTC timestamp using the reporting date and stores it as `sla_time`.

---

## Step 2: Complete the Calculator Run

The calculator has finished. Report its outcome:

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/v1/runs/run-fx-20260324-001/complete \
  -d '{
    "endTime": "2026-03-24T06:10:00Z",
    "status": "SUCCESS"
  }'
```

**Expected response — `200 OK`:**

```json
{
  "runId": "run-fx-20260324-001",
  "calculatorId": "calc-daily-fx",
  "calculatorName": "FX Rate Calculator",
  "status": "SUCCESS",
  "startTime": "2026-03-24T05:00:00Z",
  "endTime": "2026-03-24T06:10:00Z",
  "durationMs": 4200000,
  "slaBreached": false,
  "slaBreachReason": null
}
```

The run completed at 06:10 UTC (07:10 CET), which is within the 06:30 CET SLA. `slaBreached` is `false`.

---

## Step 3: Query Run Status

Retrieve the current status and recent history for `calc-daily-fx`:

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/calculators/calc-daily-fx/status?frequency=DAILY&historyLimit=5"
```

**Expected response — `200 OK`:**

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
    "slaBreached": false
  },
  "history": []
}
```

---

## Step 4: Get Analytics

Fetch 30-day runtime statistics:

```bash
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  "http://localhost:8080/api/v1/analytics/calculators/calc-daily-fx/runtime?days=30&frequency=DAILY"
```

**Expected response:**

```json
{
  "calculatorId": "calc-daily-fx",
  "periodDays": 30,
  "frequency": "DAILY",
  "avgDurationMs": 4200000,
  "avgDurationFormatted": "1hr 10mins",
  "minDurationMs": 4200000,
  "maxDurationMs": 4200000,
  "totalRuns": 1,
  "successRate": 1.0,
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

## Simulating an SLA Breach

To see how breach detection works, complete a run with an end time past the SLA:

```bash
# Start a new run
curl -u admin:admin -H "X-Tenant-Id: acme-corp" -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/v1/runs/start \
  -d '{
    "runId": "run-fx-20260324-002",
    "calculatorId": "calc-daily-fx",
    "calculatorName": "FX Rate Calculator",
    "frequency": "DAILY",
    "reportingDate": "2026-03-24",
    "startTime": "2026-03-24T05:00:00Z",
    "slaTimeCet": "06:30:00"
  }'

# Complete it after the SLA deadline (06:30 CET = 05:30 UTC)
curl -u admin:admin -H "X-Tenant-Id: acme-corp" -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/v1/runs/run-fx-20260324-002/complete \
  -d '{
    "endTime": "2026-03-24T07:00:00Z",
    "status": "SUCCESS"
  }'
```

**Response includes breach details:**

```json
{
  "runId": "run-fx-20260324-002",
  "status": "SUCCESS",
  "slaBreached": true,
  "slaBreachReason": "END_TIME_EXCEEDED"
}
```

The breach is recorded in the `sla_breach_events` table and appears in the analytics endpoints.

---

## What's Next?

- [Ingestion API](ingestion-api.md) — Complete field reference for start and complete requests
- [Query API](query-api.md) — Batch status and cache bypass options
- [Analytics API](analytics-api.md) — SLA summaries, trends, performance cards
- [SLA Monitoring](sla-monitoring.md) — How severity is calculated and what live detection does
