# Authentication

The Observability Service uses **HTTP Basic Authentication** combined with a mandatory **tenant identifier header** on every request.

---

## Required Headers

Every API request (except the health check) must include:

| Header | Required | Description |
|--------|----------|-------------|
| `Authorization` | Yes (non-local) | HTTP Basic credentials |
| `X-Tenant-Id` | Always | Tenant scoping identifier |

---

## HTTP Basic Authentication

Credentials are configured via environment variables:

| Environment Variable | Default | Description |
|----------------------|---------|-------------|
| `OBS_BASIC_USER` | `admin` | Username |
| `OBS_BASIC_PASSWORD` | `admin` | Password |

### Encoding Your Credentials

HTTP Basic authentication requires `Base64(username:password)` in the Authorization header:

```bash
# Encode credentials
echo -n "admin:admin" | base64
# Output: YWRtaW46YWRtaW4=
```

### Request Example

```http
POST /api/v1/runs/start HTTP/1.1
Host: localhost:8080
Authorization: Basic YWRtaW46YWRtaW4=
X-Tenant-Id: acme-corp
Content-Type: application/json
```

### cURL Example

```bash
curl -u admin:admin \
     -H "X-Tenant-Id: acme-corp" \
     -H "Content-Type: application/json" \
     http://localhost:8080/api/v1/calculators/calc-1/status?frequency=DAILY
```

---

## Tenant Identifier

The `X-Tenant-Id` header is mandatory on all protected endpoints. It scopes all data reads and writes to that tenant:

- All DB queries include a `WHERE tenant_id = ?` clause
- The service validates that run data belongs to the calling tenant — a 403 is returned if `tenantId` does not match

!!! warning "Trust Model"
    `X-Tenant-Id` is a caller-supplied string header. There is no cryptographic binding between the auth token and the tenant identifier. All callers with valid credentials can supply any tenant ID. This is acceptable for internal Airflow integrations operating within a trusted network.

---

## Unauthenticated Endpoints

The following endpoints do **not** require authentication:

| Path | Notes |
|------|-------|
| `GET /api/v1/health` | Health check for load balancers and probes |
| `GET /swagger-ui.html` | Interactive API documentation |
| `GET /api-docs` | OpenAPI JSON specification |
| `GET /v3/api-docs/**` | OpenAPI spec variants |

---

## Local Profile Note

When running with `SPRING_PROFILES_ACTIVE=local`, Spring Security auto-configuration is **disabled**. This means:

- `Authorization` header is not checked
- Actuator endpoints (`/actuator/*`) are open
- `X-Tenant-Id` is still required by the application logic (Spring returns `400` if absent)

!!! danger "Never run the local profile in production"
    The `local` profile removes all authentication guards. It is only intended for local development.

---

## Security Configuration Summary

| Setting | Value |
|---------|-------|
| Auth scheme | HTTP Basic |
| Password encoding | `{noop}` (plaintext) — see [TD-7](../spec/tech-debt.md#td-7-basic-auth-password-is-plaintext) |
| Default credentials | `admin` / `admin` |
| Role required | `USER` |
| Session | Stateless |
| CORS | Not configured (internal service only) |
