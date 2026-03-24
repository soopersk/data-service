# Observability Service

The **Observability Service** is an enterprise-grade backend platform that tracks the full lifecycle of Apache Airflow calculator runs, monitors SLA compliance in real time, stores partitioned historical execution data, and exposes analytics APIs for trend analysis and breach reporting.

---

## What Does It Do?

| Capability | Description |
|------------|-------------|
| **Run Lifecycle Tracking** | Accepts `start` and `complete` events from Airflow and persists them in a partitioned PostgreSQL table |
| **SLA Evaluation** | Detects SLA breaches synchronously on completion and asynchronously via a background polling job |
| **Redis-Accelerated Queries** | Write-through cache keeps status queries sub-millisecond under normal conditions |
| **Analytics** | Pre-aggregated trend data, SLA summaries, breach detail logs, and performance card payloads |
| **Multi-tenancy** | All data is scoped by `tenantId`; every request carries an `X-Tenant-Id` header |

---

## Navigation

<div class="grid cards" markdown>

- :material-book-open-variant: **[User Guide](user/index.md)**

    Get started quickly. Covers authentication, local setup, and how to call each API endpoint with examples.

- :material-cog: **[Technical Specification](spec/index.md)**

    Deep-dive into architecture, data models, Redis key design, SLA detection, performance budgets, failure modes, and extension guidelines.

</div>

---

## Quick Links

=== "User Guide"

    - [Local Setup](user/local-setup.md) — Docker Compose + run instructions
    - [Authentication](user/authentication.md) — HTTP Basic auth + required headers
    - [Ingestion API](user/ingestion-api.md) — Start and complete a run
    - [Query API](user/query-api.md) — Status and batch queries
    - [Analytics API](user/analytics-api.md) — Runtime, SLA, trends, performance card
    - [SLA Monitoring](user/sla-monitoring.md) — How SLA is evaluated and reported

=== "Technical Spec"

    - [Architecture Overview](spec/index.md) — Layered design, event flow, scheduled jobs
    - [Data Architecture](spec/data-architecture.md) — PostgreSQL schema, partitioning, indexes
    - [Redis Architecture](spec/redis-architecture.md) — Key patterns, TTLs, consistency
    - [API Reference](spec/api-reference.md) — Full contract for every endpoint
    - [SLA Architecture](spec/sla-architecture.md) — On-write evaluation + live detection
    - [Performance Model](spec/performance.md) — Latency budgets, threading, bottlenecks
    - [Configuration](spec/configuration.md) — All properties and environment variables
    - [Failure Modes](spec/failure-modes.md) — Redis outage, DB outage, partial writes
    - [Technical Debt](spec/tech-debt.md) — Known limitations with risk ratings

---

## Tech Stack at a Glance

| Component | Technology |
|-----------|-----------|
| Runtime | Java 17, Spring Boot 3.5.9 |
| Persistence | PostgreSQL 17 (Azure Flexible Server), Flyway migrations |
| Cache | Redis (Lettuce client, write-through pattern) |
| Orchestration caller | Apache Airflow |
| Infrastructure | Docker (local), AKS (cloud) |
| Build | Maven |
| Observability | Micrometer + Prometheus |
| API Docs | SpringDoc OpenAPI / Swagger UI |

---

!!! info "API Documentation (Live)"
    When the service is running locally, interactive Swagger UI is available at:

    - **Swagger UI**: `http://localhost:8080/swagger-ui.html`
    - **OpenAPI JSON**: `http://localhost:8080/api-docs`
    - **Health**: `http://localhost:8080/actuator/health`
