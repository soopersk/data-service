# User Guide

This guide covers everything you need to integrate with and operate the Observability Service — from local setup to calling every API endpoint with real examples.

---

## Who Is This Guide For?

| Audience | What You'll Find |
|----------|-----------------|
| **Airflow DAG authors** | How to instrument `start` and `complete` calls in your DAGs |
| **Dashboard / UI developers** | Status query and analytics endpoint contracts with response shapes |
| **Operators / SREs** | Local setup, health endpoints, metrics, and SLA monitoring behaviour |

---

## Guide Sections

### Getting Started

1. [Local Setup](local-setup.md) — Start the service locally with Docker Compose in under 5 minutes
2. [Authentication](authentication.md) — HTTP Basic auth and required request headers
3. [Quick Start](quickstart.md) — Complete worked example: start a run, complete it, query its status

### API Guides

4. [Ingestion API](ingestion-api.md) — `POST /runs/start` and `POST /runs/{runId}/complete`
5. [Query API](query-api.md) — Single-calculator status and batch status queries
6. [Analytics API](analytics-api.md) — Runtime stats, SLA summaries, trend data, performance card
7. [SLA Monitoring](sla-monitoring.md) — Understanding SLA evaluation, severity levels, and breach events

---

## Common Headers

Every request (except health) requires two headers:

```http
Authorization: Basic YWRtaW46YWRtaW4=
X-Tenant-Id: your-tenant-id
```

!!! tip "Base64 encoding"
    The default credentials are `admin:admin`. Their Base64 encoding is `YWRtaW46YWRtaW4=`.
    Replace with your own credentials in non-local environments.

---

## Base URL

| Environment | Base URL |
|-------------|----------|
| Local | `http://localhost:8080` |
| Dev | Configured via environment variable |
| Prod | Configured via environment variable |

---

## Response Format

All API responses use `Content-Type: application/json`.

### Success responses

Status codes follow HTTP semantics:

| Code | Meaning |
|------|---------|
| `200 OK` | Successful retrieval or update |
| `201 Created` | New run started; `Location` header points to the run |

### Error responses

```json
{
  "timestamp": "2026-02-22T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "endTime must be after startTime"
}
```

Validation failures return field-level detail:

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
| `400 Bad Request` | Missing required field, validation failure, or business rule violation |
| `401 Unauthorized` | Missing or invalid `Authorization` header |
| `403 Forbidden` | `tenantId` in request does not match authenticated tenant |
| `404 Not Found` | Run or calculator not found |
| `500 Internal Server Error` | Unhandled server-side error |
