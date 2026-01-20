# EPIC 0: Foundations & Architecture Governance

## 🎯 Goal
Establish architectural clarity, security posture, and delivery standards.

---

## Issue 0.1 – Define Architecture & NFRs

### Description
- Finalize architecture diagrams (C4 + sequence)
- Define Non-Functional Requirements (NFRs):
    - Latency
    - Throughput
    - Availability
    - Data retention
- Decide:
    - SLA thresholds
    - Polling intervals

### Deliverables
- Architecture documentation
- NFR checklist

---

## Issue 0.2 – Define API Contracts (OpenAPI)

### Description
Create OpenAPI specifications for:
- Run ingestion
- Run completion
- Status query
- Batch query
- Error model
- API versioning strategy

### Deliverables
- `openapi.yaml`

---

## Issue 0.3 – Define Data Model & Partition Strategy

### Description
- PostgreSQL schema design
- Partitioning strategy:
    - Partition by `reporting_date`
- Indexing strategy
- Retention and archival policy

### Deliverables
- Database schema documentation
- Partitioning and indexing plan
- Retention & archival guidelines

---

## Issue 0.4 – Security & Identity Design

### Description
- Azure AD application registrations
- JWT claims model
- Role separation:
    - `AIRFLOW`
    - `UI_READER`
    - `ADMIN`

### Deliverables
- Security architecture documentation
- Identity and access model

---

# EPIC 1: Azure Infrastructure Provisioning (Terraform)

---

## Issue 1.1 – Terraform Project Bootstrap

**Estimate:** 3 SP

### Description
Create a standardized Terraform repository with remote state management, environment isolation, and baseline conventions.

### Acceptance Criteria
- Terraform backend configured in Azure Storage
- State locking enabled
- Folder structure supports `dev / qa / prod`
- Terraform version pinned
- `terraform plan` runs successfully

---

## Issue 1.2 – Azure Networking (VNet & Subnets)

**Estimate:** 5 SP

### Description
Provision secure networking foundation for application, cache, and database tiers.

### Acceptance Criteria
- VNet created with non-overlapping CIDR
- Separate subnets for:
    - Application
    - Redis
    - PostgreSQL
- NSGs applied with least privilege
- Private endpoint support enabled

---

## Issue 1.3 – Azure PostgreSQL Flexible Server

**Estimate:** 5 SP

### Description
Provision PostgreSQL with HA, backups, and performance-ready configuration.

### Acceptance Criteria
- PostgreSQL Flexible Server deployed
- Zone-redundant HA enabled
- Automated backups configured
- SSL enforced
- Parameters tuned for connection pooling

---

## Issue 1.4 – Azure Redis Cache

**Estimate:** 4 SP

### Description
Provision Redis cache optimized for SLA tracking and hot reads.

### Acceptance Criteria
- Redis Premium tier deployed
- SSL enforced
- Eviction policy documented
- Persistence configured (if required)
- Connection tested from App subnet

---

## Issue 1.5 – Application Hosting (App Service / AKS)

**Estimate:** 5 SP

### Description
Provision compute layer with autoscaling and managed identity.

### Acceptance Criteria
- App Service Plan or AKS cluster deployed
- Managed Identity enabled
- Autoscaling rules configured
- Private networking enforced

---

## Issue 1.6 – Monitoring Infrastructure

**Estimate:** 3 SP

### Description
Provision Azure Monitor, Log Analytics, and Application Insights.

### Acceptance Criteria
- Log Analytics workspace created
- Application Insights linked to app
- Logs visible in Azure Portal
- Retention policies defined

---

## Issue 1.7 – Azure Key Vault

**Estimate:** 3 SP

### Description
Provision Key Vault and integrate with application identity.

### Acceptance Criteria
- Key Vault deployed
- Secrets created (DB, Redis)
- App identity granted read access
- No secrets stored in Terraform state

---

# 🟦 EPIC 2: CI/CD & DevEx (ADO Pipelines)

---

## Issue 2.1 – Maven Build Pipeline

**Estimate:** 3 SP

### Description
Create CI pipeline for compiling, testing, and packaging the service.

### Acceptance Criteria
- Maven build runs on PR
- Unit tests executed
- Build artifacts published
- Pipeline fails on test failure

---

## Issue 2.2 – Static Code Analysis

**Estimate:** 2 SP

### Description
Integrate SonarQube or equivalent.

### Acceptance Criteria
- Code quality gate enforced
- Coverage threshold defined
- Pipeline blocks on critical issues

---

## Issue 2.3 – Deployment Pipeline

**Estimate:** 5 SP

### Description
Automated deployment to `dev / qa / prod` environments.

### Acceptance Criteria
- Environment-specific variables supported
- Manual approval for prod
- Zero-downtime deployment strategy
- Rollback documented

---

## Issue 2.4 – Terraform Pipeline

**Estimate:** 4 SP

### Description
CI/CD pipeline for Terraform plan & apply.

### Acceptance Criteria
- `plan` runs on PR
- `apply` gated by approval
- Drift detection enabled
- State stored remotely

---

## Issue 2.5 – Secrets Injection

**Estimate:** 2 SP

### Description
Securely inject secrets into runtime.

### Acceptance Criteria
- Secrets resolved from Key Vault
- No secrets in logs
- Rotation tested

---

# 🟦 EPIC 3: Core Observability Service – Foundation Code

---

## Issue 3.1 – Spring Boot Project Setup

**Estimate:** 3 SP

### Description
Bootstrap Spring Boot project with standard layering.

### Acceptance Criteria
- Multi-module structure
- Logging configured
- Health endpoints exposed

---

## Issue 3.2 – Security Configuration (Azure AD)

**Estimate:** 5 SP

### Description
Implement JWT authentication and RBAC.

### Acceptance Criteria
- Azure AD JWT validated
- Roles enforced per endpoint
- Unauthorized access rejected

---

## Issue 3.3 – Database Access Layer

**Estimate:** 4 SP

### Description
Implement JPA entities and repositories.

### Acceptance Criteria
- Entities mapped correctly
- Flyway migrations applied
- Partition-aware queries implemented

---

## Issue 3.4 – Redis Cache Abstraction

**Estimate:** 4 SP

### Description
Create Redis service abstraction for caching & SLA tracking.

### Acceptance Criteria
- RedisTemplate configured
- TTL strategy implemented
- Fail-open behavior verified

---

## Issue 3.5 – Observability & Metrics

**Estimate:** 3 SP

### Description
Expose metrics for ingestion, query, and SLA detection.

### Acceptance Criteria
- Micrometer metrics exposed
- Metrics visible in Azure Monitor
- Cardinality controlled

---

# 🟦 EPIC 4: Run Ingestion APIs

---

## Issue 4.1 – Run Start API

**Estimate:** 5 SP

### Description
Implement run start ingestion with cache write-through.

### Acceptance Criteria
- Run persisted in DB
- Redis updated
- SLA registered
- Idempotency enforced

---

## Issue 4.2 – Run Completion API

**Estimate:** 5 SP

### Description
Implement run completion logic.

### Acceptance Criteria
- Status updated correctly
- Duration computed
- Cache updated
- SLA deregistered

---

## Issue 4.3 – Idempotency Handling

**Estimate:** 3 SP

### Description
Protect ingestion APIs from duplicate calls.

### Acceptance Criteria
- Duplicate requests detected
- Safe replays supported
- Conflict responses returned

---

## Issue 4.4 – Ingestion Load Testing

**Estimate:** 3 SP

### Description
Validate ingestion under peak load.

### Acceptance Criteria
- Sustains expected TPS
- No DB saturation
- Redis latency within SLO

---

# 🟦 EPIC 5: SLA Monitoring & Alerting

---

## Issue 5.1 – SLA Registration Model

**Estimate:** 3 SP

### Description
Implement Redis ZSET-based SLA tracking.

### Acceptance Criteria
- SLA deadlines stored in ZSET
- Metadata retrievable
- Entries cleaned up on completion

---

## Issue 5.2 – SLA Breach Detection Job

**Estimate:** 5 SP

### Description
Scheduled job to detect breaches.

### Acceptance Criteria
- No DB scans
- Batched ZSET queries
- Safe retry logic

---

## Issue 5.3 – SLA Breach Persistence

**Estimate:** 3 SP

### Description
Persist SLA breaches for audit.

### Acceptance Criteria
- Breach events stored
- No duplicates
- Queryable by time range

---

## Issue 5.4 – Alert Dispatching

**Estimate:** 4 SP

### Description
Send alerts via Azure Monitor.

### Acceptance Criteria
- Alerts sent on breach
- Retry on transient failures
- Failure logged and visible

---

# 🟦 EPIC 6: Query APIs & Read Models

---

## Issue 6.1 – Single Calculator Status API

**Estimate:** 5 SP

### Description
Low-latency status query endpoint.

### Acceptance Criteria
- Redis hit < 10ms
- DB fallback works
- TTL enforced

---

## Issue 6.2 – Batch Status API

**Estimate:** 5 SP

### Description
Efficient batch querying for dashboards.

### Acceptance Criteria
- Redis pipelining used
- DB queries batched
- Partial cache hits supported

---

## Issue 6.3 – Bloom Filter Optimization

**Estimate:** 3 SP

### Description
Avoid unnecessary DB lookups.

### Acceptance Criteria
- Bloom filter implemented
- False positives tolerated
- False negatives avoided

---

## Issue 6.4 – Query Performance Testing

**Estimate:** 3 SP

### Description
Validate UI-facing performance.

### Acceptance Criteria
- P95 latency meets SLO
- Cache hit ratio tracked
- Backpressure handled

---

# 🟦 EPIC 7: Reliability, Scale & Hardening

---

## Issue 7.1 – Resilience Patterns

**Estimate:** 4 SP

### Description
Protect system from dependency failures.

### Acceptance Criteria
- Circuit breakers configured
- Timeouts enforced
- Graceful degradation verified

---

## Issue 7.2 – Data Retention & Archival

**Estimate:** 3 SP

### Description
Manage long-term data growth.

### Acceptance Criteria
- Old partitions archived
- Retention policy enforced
- No impact on hot queries

---

## Issue 7.3 – Disaster Recovery Testing

**Estimate:** 4 SP

### Description
Validate recovery from failures.

### Acceptance Criteria
- Redis restart handled
- DB restore tested
- RTO / RPO documented

---

## Issue 7.4 – Security Hardening & Threat Modeling

**Estimate:** 4 SP

### Description
Final security validation.

### Acceptance Criteria
- Threat model reviewed
- Pen test findings addressed
- JWT misuse scenarios tested

---

# 📊 Total Effort Summary (Rough Order of Magnitude)

| Epic  | Story Points |
|------:|--------------:|
| Epic 1 | 28 |
| Epic 2 | 16 |
| Epic 3 | 19 |
| Epic 4 | 16 |
| Epic 5 | 18 |
| Epic 6 | 16 |
| Epic 7 | 15 |
| **Total** | **128 SP** |

**≈ 4–5 months** with a **5-person team**.

