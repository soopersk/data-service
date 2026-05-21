# Ingestion API

The ingestion API is the primary write path for the Observability Service. It is called by Apache Airflow (or any compatible orchestrator) to record the start and completion of calculator runs.

---

## POST /api/v1/runs/start

Records a calculator run start event.

### Request

```
POST /api/v1/runs/start
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
Content-Type: application/json
```

#### Request Body Fields

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `runId` | String | Yes | Not blank | Unique identifier for this run. Must be unique per tenant. |
| `calculatorId` | String | Yes | Not blank | Calculator identifier. Used for grouping and analytics. |
| `calculatorName` | String | Yes | Not blank | Human-readable name. Immutable — only set on the first insert. |
| `frequency` | String | Yes | `DAILY`, `D`, `MONTHLY`, or `M` | Run frequency. Determines partition query windows and SLA behaviour. |
| `reportingDate` | Date | Yes | `YYYY-MM-DD` | The reporting date for this run. This is the partition key — always provide it. |
| `startTime` | Instant | Yes | ISO-8601 UTC | When the run started. Example: `2026-03-24T05:00:00Z` |
| `slaTime` | Instant | No | ISO-8601 UTC | **Optional.** Legacy upstream deadline. The graded SLA deadline is derived from the calculator's average runtime; `slaTime` is only the weakest baseline fallback (no history, no `expectedDurationMs`). |
| `expectedDurationMs` | Long | No | Positive | Expected run duration (ms). Used as a duration baseline when history is thin, and for the estimated-end fallback. |
| `estimatedStartTime` | Instant | No | ISO-8601 UTC | Estimated start. If omitted, derived from the calculator's historical average start, else falls back to `startTime`. |
| `estimatedEndTime` | Instant | No | ISO-8601 UTC | Estimated end. If omitted, derived from `start + expectedDurationMs`, else `estimatedStart + average duration`. |
| `runNumber` / `runType` / `region` | String | No | | Promoted dimensional fields (also accepted inside `runParameters`). |
| `correlationId` | String | No | | Marks physical splits of one logical run. |
| `runParameters` | Object | No | Any JSON object | Arbitrary key-value metadata stored as JSONB. |
| `additionalAttributes` | Object | No | Any JSON object | Additional run metadata stored as JSONB. |

!!! info "Immutable fields"
    `calculatorName`, `startTime`, `slaTime`, `expectedDurationMs`, `estimatedStartTime`, `estimatedEndTime` are **immutable after the first insert**. Subsequent `start` calls with the same `runId` + `reportingDate` return the existing run (idempotency) — these fields are not updated.

### Response

**`201 Created`** — New run created.

**`200 OK`** — Duplicate `(runId, reportingDate)` — existing run returned unchanged.

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

The `Location` response header is set to `/api/v1/runs/{runId}`.

### Idempotency

The start endpoint is fully idempotent. Submitting the same `(runId, reportingDate)` pair multiple times is safe — the second and subsequent calls return the existing run without modification. This handles Airflow retry scenarios transparently.

### SLA Deadline Derivation at Start

There is **no start-time breach** (duration-based model). At start the service derives the SLA deadline from the calculator's average runtime and freezes it into `slaTime`; the run is created `RUNNING` with `slaBreached=false`. Both DAILY and MONTHLY runs are registered for live monitoring when a deadline is derived. Grading happens at completion (and via live detection) against the frozen deadline. See [SLA Monitoring](sla-monitoring.md).

### cURL Example

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
    "expectedDurationMs": 3600000,
    "estimatedStartTime": "2026-03-24T05:00:00Z",
    "runParameters": {
      "sourceSystem": "bloomberg",
      "assetClass": "FX"
    }
  }'
```

---

## POST /api/v1/runs/{runId}/complete

Records run completion and triggers SLA evaluation.

### Request

```
POST /api/v1/runs/{runId}/complete
Authorization: Basic <base64>
X-Tenant-Id: <tenant>
Content-Type: application/json
```

#### Path Parameter

| Parameter | Description |
|-----------|-------------|
| `runId` | The run ID originally provided in the `start` call |

#### Request Body Fields

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `endTime` | Instant | Yes | ISO-8601 UTC, after `startTime` | When the run ended. |
| `status` | String | No | `SUCCESS`, `FAILED`, `TIMEOUT`, `CANCELLED` | Completion status. Defaults to `SUCCESS` if omitted or blank. |

### Response

**`200 OK`** — Run completed. SLA evaluation result included.

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

When an SLA breach is detected:

```json
{
  "runId": "run-fx-20260324-002",
  "status": "SUCCESS",
  "endTime": "2026-03-24T07:00:00Z",
  "durationMs": 7200000,
  "slaBreached": true,
  "slaBreachReason": "Finished 22 minutes late (LATE band)"
}
```

### Idempotency

If the run is not in `RUNNING` status when `complete` is called, the existing run is returned unchanged. This handles Airflow duplicate delivery gracefully.

### SLA Evaluation

SLA evaluation runs synchronously during this call. The run's **actual duration** is classified once against the frozen `slaTime` (derived at start from the calculator's average runtime):

| Condition | Status | Severity |
|-----------|--------|----------|
| `status = FAILED` or `TIMEOUT` | breach | CRITICAL |
| `duration ≤ slaTime − startTime` (buffered + lateBand) | ON_TIME | — |
| `duration ≤ that + bandGap` (veryLateBand − lateBand) | LATE | MEDIUM |
| beyond | VERY_LATE | HIGH |

See [SLA Monitoring](sla-monitoring.md) for the full breach lifecycle.

### cURL Examples

```bash
# Successful completion within SLA
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/v1/runs/run-fx-20260324-001/complete \
  -d '{"endTime": "2026-03-24T06:10:00Z", "status": "SUCCESS"}'

# Failed run
curl -u admin:admin \
  -H "X-Tenant-Id: acme-corp" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/v1/runs/run-fx-20260324-001/complete \
  -d '{"endTime": "2026-03-24T06:10:00Z", "status": "FAILED"}'
```

---

## Airflow Integration Pattern

A typical Airflow DAG integration looks like this:

```python
import requests
from datetime import datetime

BASE_URL = "http://observability-service:8080"
HEADERS = {
    "Authorization": "Basic YWRtaW46YWRtaW4=",
    "X-Tenant-Id": "acme-corp",
    "Content-Type": "application/json"
}

def start_run(run_id: str, calc_id: str, reporting_date: str):
    payload = {
        "runId": run_id,
        "calculatorId": calc_id,
        "calculatorName": "My Calculator",
        "frequency": "DAILY",
        "reportingDate": reporting_date,
        "startTime": datetime.utcnow().isoformat() + "Z",
        "expectedDurationMs": 3600000
    }
    resp = requests.post(f"{BASE_URL}/api/v1/runs/start", json=payload, headers=HEADERS)
    resp.raise_for_status()
    return resp.json()

def complete_run(run_id: str, status: str = "SUCCESS"):
    payload = {
        "endTime": datetime.utcnow().isoformat() + "Z",
        "status": status
    }
    resp = requests.post(
        f"{BASE_URL}/api/v1/runs/{run_id}/complete",
        json=payload,
        headers=HEADERS
    )
    resp.raise_for_status()
    return resp.json()
```

---

## Frequency Values

| Value | Accepted Aliases | Behaviour |
|-------|-----------------|-----------|
| `DAILY` | `D` | Query window: last 3 days. SLA live monitoring enabled. |
| `MONTHLY` | `M` | Query window: last 13 months. SLA live monitoring not enabled. |

Unknown or null frequency values default to `DAILY`.
