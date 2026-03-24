# Technical Specification

**Version:** 1.0
**Stack:** Java 17 · Spring Boot 3.5.9 · PostgreSQL 17 (Azure Flexible Server) · Redis · Flyway
**Audience:** Senior engineers, platform team, onboarding architects

---

## System Overview

The Observability Service is an enterprise-grade backend that tracks the lifecycle of Apache Airflow calculator runs, monitors SLA compliance in real time, stores historical execution data, and exposes analytics APIs for trend analysis and breach reporting.

### Responsibilities

- Accept run lifecycle events from Airflow via HTTP (start / complete)
- Evaluate SLA compliance both on-write and continuously via scheduled detection
- Store all run data in a partitioned PostgreSQL table with daily granularity
- Cache status data in Redis to reduce DB read pressure
- Expose OLTP-class query endpoints for current status and batch queries
- Expose analytics endpoints for runtime trends, SLA summaries, breach detail, and performance cards
- Emit Spring application events for downstream alerting

### Architectural Style

**Event-driven, layered REST service.**

| Path | Description |
|------|-------------|
| **Synchronous** | Controller → Service → Repository → DB + Redis write |
| **Asynchronous** | Spring `ApplicationEventPublisher` → `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` handlers |
| **Background** | `@Scheduled` jobs for SLA monitoring and partition management |

### Core Design Principles

| Principle | Implementation |
|-----------|----------------|
| No ORM | All persistence via `NamedParameterJdbcTemplate` and manual `RowMapper`s |
| Idempotency | All DB writes use `INSERT ... ON CONFLICT (run_id, reporting_date) DO UPDATE` |
| Partition safety | `reporting_date` must be included in every `calculator_runs` query |
| Multi-tenancy | `X-Tenant-Id` header on every API request; all SQL filters by `tenant_id` |
| Write-through cache | Redis is updated immediately on every write; DB is source of truth |
| Event isolation | Alert persistence runs in a new transaction after the originating transaction commits |

### Explicit Non-Goals

- No JPA / Hibernate — zero entity classes
- No distributed transactions
- No message broker (Kafka, RabbitMQ) — events are in-process Spring events only
- No user management — single in-memory Basic Auth user per deployment
- No multi-region coordination

---

## System Diagram

```
┌────────────────────────────────────────────────────────────────────┐
│                          Airflow / Callers                          │
│              (HTTP Basic Auth + X-Tenant-Id header)                 │
└─────────────────────────────┬──────────────────────────────────────┘
                              │
            ┌─────────────────▼──────────────────┐
            │           Spring Boot 3.5.9         │
            │                                     │
            │  ┌──────────────────────────────┐   │
            │  │         Controllers          │   │
            │  │  RunIngestionController       │   │
            │  │  RunQueryController           │   │
            │  │  AnalyticsController          │   │
            │  │  HealthController             │   │
            │  └───────────────┬──────────────┘   │
            │                  │                   │
            │  ┌───────────────▼──────────────┐   │
            │  │           Services           │   │
            │  │  RunIngestionService          │   │
            │  │  RunQueryService              │   │
            │  │  AnalyticsService             │   │
            │  │  SlaEvaluationService         │   │
            │  │  AlertHandlerService          │   │
            │  │  CacheWarmingService          │   │
            │  └────────┬──────────┬───────────┘   │
            │           │          │               │
            │  ┌────────▼──┐  ┌───▼────────────┐  │
            │  │Repositories│  │  Redis Cache   │  │
            │  │CalculatorRun  │  RedisCalculator  │  │
            │  │ DailyAgg   │  │  Cache          │  │
            │  │ SlaBreachEv│  │  SlaMonitoring  │  │
            │  └────────┬──┘  │  Cache          │  │
            │           │     │  Analytics      │  │
            │           │     │  CacheService   │  │
            │           │     └───────┬─────────┘  │
            │  ┌────────▼──┐         │             │
            │  │PostgreSQL │◄────────┘             │
            │  │(partitioned│  (read-through        │
            │  │  by date)  │   fallback)           │
            │  └───────────┘                        │
            │                                       │
            │  ┌───────────────────────────────┐   │
            │  │      Scheduled Jobs           │   │
            │  │  LiveSlaBreachDetectionJob     │   │
            │  │  PartitionManagementJob        │   │
            │  └───────────────────────────────┘   │
            └─────────────────────────────────────┘
```

---

## Specification Sections

| Section | Content |
|---------|---------|
| [Architecture](architecture.md) | Controller-Service-Repository layering, event flow, scheduled jobs, Redis interaction model |
| [Data Architecture](data-architecture.md) | PostgreSQL schema, partitioning strategy, indexes, query window logic, partition safety audit |
| [Redis Architecture](redis-architecture.md) | Key patterns, TTLs, write/read paths, serialization, consistency guarantees |
| [API Reference](api-reference.md) | Complete contract for every endpoint including request/response shapes and error codes |
| [Ingestion & Event Flow](ingestion-flow.md) | Step-by-step walkthrough of `startRun()` and `completeRun()` with sequence diagrams |
| [SLA Architecture](sla-architecture.md) | On-write evaluation, live detection job, dual-mechanism rationale |
| [Performance Model](performance.md) | Threading model, HikariCP config, Redis latency budget, per-endpoint latency estimates |
| [Configuration & Profiles](configuration.md) | All custom properties, environment variables, and profile differences |
| [Observability](observability.md) | Actuator endpoints, all Micrometer metrics, request correlation, known gaps |
| [Failure Modes & Recovery](failure-modes.md) | Redis outage, DB outage, partial writes, SLA job crash, partition creation failure |
| [Extension Guide](extension-guide.md) | How to add endpoints, frequencies, SLA policies, schema changes, Redis keys, indexes |
| [Technical Debt](tech-debt.md) | TD-1 through TD-11 with risk ratings and recommended fixes |
