🟦 EPIC A: Observability Platform – Infrastructure Provisioning

Purpose
Everything required to securely and reliably run the Observability service in Azure.

🟩 Issue A1 – Terraform Bootstrap & Backend (3 SP) [KR 3.5]

Description
Set up Terraform backend, state management, and bootstrap configuration for Observability infrastructure.

Acceptance Criteria

Terraform backend configured with remote state

State locking enabled

Environment separation supported (dev/uat/prod)

Bootstrap module committed to repo

Terraform init succeeds from clean environment

🟩 Issue A2 – Azure Resource Configuration (5 SP) [KR 3.4]

Description
Provision core Azure resources required to host the Observability service.

Acceptance Criteria

Resource group created

Networking, identity, vnet and other resource definitions configured and segregated by environment (dev/uat/prod)

Resources follow naming and tagging standards

UMI roles assigned

Terraform plan succeeds without manual steps

🟩 Issue A3 – PostgreSQL Provisioning (5 SP) [KR 3.5]

Description
Provision and configure Azure PostgreSQL Flexible Server for Observability data storage.

Acceptance Criteria

PostgreSQL instance provisioned via Terraform

Technical account created and configured with database

HA enabled for UAT/PROD

SSL enforced

Connectivity validated

🟩 Issue A4 – Redis Provisioning (4 SP) [KR 3.1]

Description
Provision Azure Redis cache for low-latency Observability operations.

Acceptance Criteria

Redis instance provisioned via Terraform

Secure access configured

TLS enabled

Connection tested from application

Microsoft Entra Authentication enabled

🟩 Issue A5 – EVA Integration (3 SP) [KR 3.4]

Description
Integrate infrastructure with EVA for secrets, identity, or policy enforcement.

Acceptance Criteria

Required EVA components integrated

Access validated for Observability service

No hard-coded secrets in code or Terraform

Integration documented

🟩 Issue A6 – Terraform GitLab Build Pipeline (4 SP) [KR 3.5]

Description
Create CI pipeline for compiling, testing, and packaging the service.

Acceptance Criteria

Maven build runs on MR

Build artifacts published to Nexus

🟩 Issue A7 – ADO Setup & Pipelines (5 SP) [KR 1.4]

Description
Configure Azure DevOps pipelines for infrastructure and application lifecycle.

Acceptance Criteria

Build and deploy pipelines created

TF scripts pulled from Nexus and executed in ADO pipeline

Environment separation supported (dev/uat/prod)

Secrets pulled securely

Manual approval for apply stage

Pipeline execution validated end-to-end

🟩 Issue A8 – Infrastructure Setup Full Testing (5 SP) [KR 3.1]

Description
Validate end-to-end infrastructure readiness.

Acceptance Criteria

All provisioned resources reachable

Security controls validated

Failure scenarios tested

Infrastructure readiness sign-off recorded

🟦 EPIC B: Observability Platform – Core Service Foundation

Purpose
Runnable, secure Spring Boot service with operational baseline.

🟩 Issue B1 – Spring Boot Bootstrap (3 SP)

Acceptance Criteria

Application starts successfully

Standard project structure used

Build passes in CI

Base README created

🟩 Issue B2 – Azure AD JWT Security (5 SP)

Acceptance Criteria

JWT validation implemented

Unauthorized requests rejected

Roles/scopes configurable

Security tests pass

🟩 Issue B3 – Health & Readiness Endpoints (2 SP)

Acceptance Criteria

/health and /ready endpoints implemented

Dependency health checks included

Compatible with Kubernetes probes

🟩 Issue B4 – DB Schema & Flyway (4 SP)

Acceptance Criteria

Flyway configured

Initial schema migration created

Migrations run automatically on startup

Rollback strategy documented

🟩 Issue B5 – Redis Abstraction (4 SP)

Acceptance Criteria

Redis client configured

Abstraction hides implementation details

Unit tests cover abstraction

Failures handled gracefully

🟩 Issue B6 – Metrics Baseline (3 SP)

Acceptance Criteria

JVM and HTTP metrics enabled

Metrics exposed via endpoint

Metrics visible in Azure Monitor

🟩 Issue B7 – Logging Standardization (2 SP)

Acceptance Criteria

Correlation IDs supported

Log format standardized

Logs searchable in central system

🟩 Issue B8 – Configuration Management (2 SP)

Acceptance Criteria

Config sourced from env / Key Vault

No secrets in code

Config overrides documented

🟩 Issue B9 – Service Smoke Test (2 SP)

Acceptance Criteria

Smoke test validates startup

Fails on missing dependencies

Runs in CI pipeline

🟦 EPIC C: Observability – Run Ingestion (Write Path)

Purpose
External systems reliably report run lifecycle.

🟩 Issue C1 – Run Start API (5 SP)

Acceptance Criteria

API accepts run start payload

Validates input

Persists run metadata

Idempotent behavior documented

🟩 Issue C2 – Run Completion API (5 SP)

Acceptance Criteria

Completion updates run state

Handles duplicate requests

SLA evaluation triggered

Errors handled correctly

🟩 Issue C3 – Idempotency Keys (3 SP)

Acceptance Criteria

Idempotency keys supported

Duplicate requests ignored

Behavior documented

🟩 Issue C4 – Write-through Redis Cache (4 SP)

Acceptance Criteria

Cache updated atomically with DB

Cache consistency maintained

Cache failure does not break writes

🟩 Issue C5 – SLA Registration Logic (3 SP)

Acceptance Criteria

SLA thresholds stored

SLA identifiers linked to runs

SLA data retrievable

🟩 Issue C6 – Cache Eviction Rules (2 SP)

Acceptance Criteria

TTL configured

Eviction does not impact correctness

Rules documented

🟩 Issue C7 – Error Handling (2 SP)

Acceptance Criteria

Canonical error model used

Proper HTTP status codes

Errors logged with context

🟩 Issue C8 – OpenAPI Validation Tests (2 SP)

Acceptance Criteria

Validation tests implemented

Fail on contract mismatch

Included in CI

🟩 Issue C9 – Security Enforcement (2 SP)

Acceptance Criteria

Unauthorized access blocked

Role-based rules applied

Security tests pass

🟩 Issue C10 – Write Path Metrics (2 SP)

Acceptance Criteria

Latency and throughput metrics emitted

Errors counted

Metrics visible in dashboards

🟩 Issue C11 – Load Test (Ingestion) (3 SP)

Acceptance Criteria

Load test executed

SLA targets met

Results documented

🟦 EPIC D: Observability – SLA Monitoring & Alerting

Purpose
Detect SLA breaches in near-real time without expensive database scans.

🟩 Issue D1 – Redis SLA ZSET Data Model (3 SP)

Acceptance Criteria

SLA ZSET schema defined and documented

ZSET keys support efficient range queries

SLA entries added and removed correctly

Unit tests validate ZSET operations

🟩 Issue D2 – SLA Scheduler Job (5 SP)

Acceptance Criteria

Scheduler triggers at configurable intervals

Job queries Redis without DB scans

Scheduler resilient to restarts

Metrics emitted

🟩 Issue D3 – SLA Breach Evaluation Logic (4 SP)

Acceptance Criteria

Breach logic correctly identifies overdue runs

Supports multiple SLA types

No duplicate breach detection

Unit tested

🟩 Issue D4 – SLA Breach Persistence (3 SP)

Acceptance Criteria

Breach records stored in database

Idempotent persistence logic

Queryable by run and SLA

Failures handled gracefully

🟩 Issue D5 – Alert Dispatch Service (4 SP)

Acceptance Criteria

Alerts triggered once per breach

Payload includes run and SLA details

Asynchronous delivery

Failures logged and retried

🟩 Issue D6 – Azure Monitor Integration (3 SP)

Acceptance Criteria

Alerts visible in Azure Monitor

Metrics exported correctly

Severity mapped

Integration validated

🟩 Issue D7 – Retry & Backoff Strategy (2 SP)

Acceptance Criteria

Retry policy implemented

Backoff configurable

No endless retries

Retries logged

🟩 Issue D8 – Alert Deduplication (2 SP)

Acceptance Criteria

Deduplication key defined

Duplicate alerts suppressed

Behavior documented and tested

🟩 Issue D9 – SLA Metrics & Logging (2 SP)

Acceptance Criteria

SLA breach count metrics emitted

Evaluation latency metrics available

Logs include run and SLA identifiers

🟩 Issue D10 – Failure Simulation & Testing (3 SP)

Acceptance Criteria

SLA breach scenarios simulated

Alert flow validated end-to-end

Failure scenarios documented

Results recorded

🟦 EPIC E: Observability – Query APIs & Read Performance

Purpose
Enable fast, scalable UI queries with high cache efficiency.

🟩 Issue E1 – Single Run Status API (5 SP)

Acceptance Criteria

Correct run state returned

Cache-first lookup

Handles missing runs

OpenAPI updated

🟩 Issue E2 – Batch Run Status API (5 SP)

Acceptance Criteria

Batch requests supported

Partial results returned

Input size limits enforced

Performance validated

🟩 Issue E3 – Redis Read-Through Cache (4 SP)

Acceptance Criteria

Cache populated on miss

Consistency maintained

DB fallback on Redis failure

Hit/miss metrics emitted

🟩 Issue E4 – TTL Strategy (2 SP)

Acceptance Criteria

TTLs configurable

No stale data

Strategy documented

🟩 Issue E5 – Bloom Filter Optimization (3 SP)

Acceptance Criteria

Bloom filter implemented

Acceptable false positive rate

Filter refreshed

Tested

🟩 Issue E6 – Partial Cache Hit Logic (3 SP)

Acceptance Criteria

Cached results returned immediately

DB fetch for misses

Combined response correct

Unit tested

🟩 Issue E7 – DB Fallback Optimization (3 SP)

Acceptance Criteria

Efficient indexes used

Query plans reviewed

Performance validated

🟩 Issue E8 – Pagination Support (2 SP)

Acceptance Criteria

Pagination parameters supported

Sorting defined

Backward compatible

🟩 Issue E9 – Query Metrics (2 SP)

Acceptance Criteria

Latency and throughput metrics

Cache hit ratio tracked

Visible in dashboards

🟩 Issue E10 – Load Test (Read Path) (3 SP)

Acceptance Criteria

Load tests executed

Targets met

Results documented

🟩 Issue E11 – Cache Hit Ratio Dashboard (2 SP)

Acceptance Criteria

Cache hit/miss visualized

Trends visible

Accessible to team

🟦 EPIC F: Observability – Reliability & Production Hardening

Purpose
Ensure Observability service is resilient, secure, and operationally ready.

🟩 Issue F1 – Circuit Breakers (4 SP)

Acceptance Criteria

Circuit breakers configured

Thresholds tunable

Behavior validated

Metrics exposed

🟩 Issue F2 – Timeout Configuration (2 SP)

Acceptance Criteria

Timeouts defined per dependency

Defaults documented

No unbounded waits

🟩 Issue F3 – Graceful Degradation (3 SP)

Acceptance Criteria

Core APIs remain available

Non-critical features degrade safely

Documented and tested

🟩 Issue F4 – Redis Failure Recovery (3 SP)

Acceptance Criteria

Redis failure detected

Fallback logic executed

Recovery validated

🟩 Issue F5 – Partition Maintenance Job (3 SP)

Acceptance Criteria

Old partitions archived or dropped

No locking issues

Maintenance documented

🟩 Issue F6 – Data Retention Rules (3 SP)

Acceptance Criteria

Retention periods configured

Old data purged

Compliance met

🟩 Issue F7 – Disaster Recovery Testing (4 SP)

Acceptance Criteria

DR scenarios executed

Recovery objectives met

Gaps tracked

🟩 Issue F8 – Security Threat Modeling (4 SP)

Acceptance Criteria

Threat model created

Risks assessed

Mitigations identified

Review completed

🟩 Issue F9 – Runbooks & Operational Alerts (3 SP)

Acceptance Criteria

Runbooks cover common incidents

Alerts actionable

On-call readiness validated