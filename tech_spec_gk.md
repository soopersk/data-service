# Observability Service Technical Specification

## Overview
The Observability Service is a backend application designed to monitor and track "calculator runs" – computational processes with defined frequencies (DAILY or MONTHLY), SLAs, and performance metrics. It supports:
- **Ingestion**: Recording start and completion of runs from external systems (e.g., Airflow, Calculators).
- **Querying**: Retrieving status, history, and analytics for calculators.
- **SLA Monitoring**: Detecting breaches and alerting via Azure Monitor.
- **Optimization**: Heavy use of Redis for caching to reduce PostgreSQL load, with write-through and read-through patterns.
- **Security**: JWT-based authentication with Azure AD, role-based access control (RBAC).
- **Scalability**: Partitioned PostgreSQL tables, asynchronous processing, and resilience patterns.

The service is built with Spring Boot, PostgreSQL (partitioned for performance), Redis (for caching and live monitoring), and integrates with OpenTelemetry for observability.

### Key Features
- Well-defined Open API json contract for all interaction points (system agnostic)
- Partitioned storage for calculator runs by reporting date.
- Intelligent caching with TTLs based on run status and frequency.
- Live SLA breach detection using Redis sorted sets.
- Batch querying for dashboards with cache batching.
- Event-driven architecture for cache eviction, warming, and alerting.
- Metrics export to Prometheus/Azure Monitor.

### Non-Functional Requirements
- **Performance**: Handle 1000+ concurrent queries with <200ms latency (via caching).
- **Availability**: 99.9% uptime, with circuit breakers and retries for alerting.
- **Scalability**: Horizontal scaling via multiple instances; Redis cluster support.
- **Security**: Tenant isolation via JWT claims; no header-based tenant spoofing.
- **Data Retention**: DAILY runs: 7 days; MONTHLY: 13 months (via partition dropping).
- **Monitoring**: Custom Micrometer metrics; OpenTelemetry traces/metrics.

## Architecture
The service follows a layered architecture:
- **Controllers**: Handle HTTP requests, secured with Spring Security.
- **Services**: Business logic, caching, event publishing.
- **Repositories**: JDBC-based for PostgreSQL; custom queries with partition awareness.
- **Caches**: Redis for write-through/read-through, bloom filters, and sorted sets.
- **Events**: Spring ApplicationEvents for async decoupling (e.g., cache eviction, alerting).
- **Configs**: Spring configurations for async, metrics, Redis, security.

```mermaid
graph LR
    ExternalSystem[Airflow/Calculator] -->|Ingest Runs| API[Observability Service]
    UI["Observability UI (AmpliFi)"] -->|Query Status| API
    API --> Redis[(Redis Cache)]
    API --> PG[(PostgreSQL Flexible Server)]
    API --> Metrics[Alert / Azure Monitor/ Gsnow]
```


### High-Level Components
- **Ingestion Flow**: Airflow → Controller → Service → Repository → Cache → Events.
- **Query Flow**: UI → Controller → Service → Cache/Repository → Response.
- **Monitoring**: Scheduled tasks for SLA detection; Redis for live tracking.

### Technology Stack
- **Language/Framework**: Java 17+, Spring Boot 3.9
- **Database**: PostgreSQL 17 (Azure Flexible Server) with partitioning.
- **Cache**: Redis 7+ with Lettuce client.
- **Security**: Spring Security with OAuth2/JWT (Azure AD).
- **Observability**: Micrometer, OpenTelemetry.
- **Resilience**: Resilience4j (circuit breaker, retry).
- **API Docs**: Springdoc OpenAPI/Swagger.

### Deployment
- Containerized (Docker/Kubernetes).
- Environment variables for configs (e.g., REDIS_HOST, POSTGRES_URL).
- Profiles: dev, prod.

## Data Models
### CalculatorRun (Core Entity)
- **Description**: Represents a single run of a calculator.

```mermaid
classDiagram    
    class CalculatorRun {
        - String runId
        - String calculatorId
        - String calculatorName
        - String tenantId
        - String frequency
        - LocalDate reportingDate
        - Instant startTime
        - Instant endTime
        - Long durationMs
        - BigDecimal startHourCet
        - BigDecimal endHourCet
        - String status
        - Instant slaTime
        - Long expectedDurationMs
        - Instant estimatedStartTime
        - Instant estimatedEndTime
        - Boolean slaBreached
        - String slaBreachReason
        - String runParameters
        - Instant createdAt
        - Instant updatedAt
    }
```
- **Table**:
```mermaid
erDiagram
    CALCULATOR_RUNS {
        VARCHAR run_id PK
        DATE reporting_date PK

        VARCHAR calculator_id
        VARCHAR calculator_name
        VARCHAR tenant_id
        VARCHAR frequency

        TIMESTAMPTZ start_time
        TIMESTAMPTZ end_time
        BIGINT duration_ms

        DECIMAL start_hour_cet
        DECIMAL end_hour_cet

        VARCHAR status

        TIMESTAMPTZ sla_time
        BIGINT expected_duration_ms
        TIMESTAMPTZ estimated_start_time
        TIMESTAMPTZ estimated_end_time

        BOOLEAN sla_breached
        TEXT sla_breach_reason

        JSONB run_parameters
        JSONB additional_attributes

        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
```
### Database Schema
- **Partitions**: calculator_runs partitioned by RANGE on reporting_date.
- **Indexes**: On Key Columns.
- **Views**: aggregated views
- **Functions**: create_calculator_run_partitions(), drop_old_calculator_run_partitions().


### DailyAggregate
- **Description**: Pre-computed daily metrics per calculator.
- **Fields**:
    - calculatorId: String (PK)
    - tenantId: String (PK)
    - dayCet: LocalDate (PK)
    - totalRuns: Integer
    - successRuns: Integer
    - slaBreaches: Integer
    - avgDurationMs: Long
    - avgStartMinCet: Integer
    - avgEndMinCet: Integer
    - computedAt: Instant

### SlaBreachEvent
- **Description**: Records SLA breaches for alerting.
- **Fields**:
    - breachId: Long (PK)
    - runId: String (Unique)
    - calculatorId: String
    - calculatorName: String
    - tenantId: String
    - breachType: String
    - expectedValue: Long
    - actualValue: Long
    - severity: String
    - alerted: Boolean
    - alertedAt: Instant
    - alertStatus: String
    - retryCount: Integer
    - lastError: String
    - createdAt: Instant

---
## APIs
### Ingestion Endpoints (Secured: RBAC role controlled)

- **POST `/api/v1/runs/start`**
    - Request: StartRunRequest (runId, calculatorId, calculatorName, frequency, reportingDate, startTime, slaTimeCet, etc.)
    - Response: RunResponse (201 Created)
    - Description: Starts a run, registers in SLA monitoring.

**Request Object**
```mermaid
classDiagram
    class StartRunRequest {
        - String runId
        - String calculatorId
        - String calculatorName
        - Frequency frequency
        - LocalDate reportingDate
        - Instant startTime
        - LocalTime slaTimeCet
        - Long expectedDurationMs
        - LocalTime estimatedStartTimeCet
        - Map<String, Object> runParameters
        - Map<String, Object> additionalAttributes
    }

    class Frequency {
        <<enum>>
        DAILY
        MONTHLY
    }

    StartRunRequest --> Frequency
```
---

- **POST `/api/v1/runs/{runId}/complete`**
    - Content-Type: application/json
    - Request: CompleteRunRequest (endTime, status)
    - Response: RunResponse
    - Description: Completes a run, evaluates SLA, updates aggregates.

---

### Query Endpoints (Secured: RBAC Roles)

- **GET `/api/v1/calculators/{calculatorId}/status?frequency=...&historyLimit=...`**
    - Response: CalculatorStatusResponse
    - Description: Single calculator status with history.


- **POST `/api/v1/calculators/batch/status`**
    - Content-Type: application/json
    - Request: List<String> calculatorIds, String frequency, Integer historyLimit, Boolean allowStale
    - Response: List<CalculatorStatusResponse>
    - Description: Batch status for dashboards.

```mermaid
classDiagram
    class CalculatorStatusResponse {
        - String calculatorName
        - Instant lastRefreshed
        - RunStatusInfo current
        - List[RunStatusInfo] history
    }

    class RunStatusInfo {
        - String runId
        - String status
        - Instant start
        - Instant end
        - Instant estimatedStart
        - Instant estimatedEnd
        - Instant sla
        - Long durationMs
        - String durationFormatted
        - Boolean slaBreached
        - String slaBreachReason
    }

    CalculatorStatusResponse "1" --> "1" RunStatusInfo : current
    CalculatorStatusResponse "1" --> "0..*" RunStatusInfo : history
```

### Health Endpoint
- **GET /api/v1/health**: Returns UP status.

---

## Flows
### Ingestion Flow: Start Run

1. **Airflow / External System calls the API**
    * Sends a `POST /runs/start` request to the Controller.
2. **Controller authorizes request via Azure AD**
    * No explicit tenant extraction; request is allowed/denied based on Azure AD authentication and roles.
3. **Controller forwards request to Service**
    * Calls `startRun(request)` with the run details.
4. **Service checks for duplicates in the database**
    * Queries the Repository with `findById(runId, reportingDate)` to see if the run already exists.
5. **If no duplicate exists** (`alt No duplicate`):
    * **Service builds and saves the `CalculatorRun`** to the database (`upsert`).
    * **Service writes the run to Redis cache** (write-through), which also handles **SLA registration**.
    * **Service publishes `RunStartedEvent`** for downstream consumers.
6. **Service returns `RunResponse` to Controller**
    * Contains status and details of the newly started run.
7. **Controller responds to Airflow / External System**
    * Returns HTTP `201 Created` with the run details.

#### Sequence Diagram

```mermaid
sequenceDiagram
    participant Airflow/ExternalSystem
    participant API/Controller
    participant Service
    participant Repository
    participant RedisCache
    participant EventPublisher

    Airflow/ExternalSystem->>API/Controller: POST /runs/start
    Note right of API/Controller: Auth via Azure AD
    API/Controller->>Service: startRun(request)
    Service->>Repository: findById (partition-aware)
    alt No duplicate
        Service->>Repository: upsert(run)
        Service->>RedisCache: cacheRunOnWrite(run) + registerForSLA
        Service->>EventPublisher: publish(RunStartedEvent)
    end
    Service-->>API/Controller: RunResponse
    API/Controller-->>Airflow/ExternalSystem: 201 Created

```

### Ingestion Flow: Complete Run

1. **Airflow / External System calls POST /complete**
    * Sends a `POST /runs/{runId}/complete` request to the Controller.
2. **Controller authorizes request via Azure AD**
    * No tenant extraction; Azure AD ensures the caller is allowed.
3. **Service finds the run**
    * Queries `Repository` with `findRecentRun(runId)` (recent partitions first).
4. **Service updates run and evaluates SLA**
    * Updates run status, end time, and any other relevant fields.
    * Calculates whether the SLA is breached.
5. **Service saves run to Repository**
    * All database persistence happens via `Repository`.
6. **Service updates Redis cache and deregisters from SLA monitoring**
    * Single RedisCache handles both caching and SLA tracking.
7. **Branch based on SLA result**:
    * **If SLA breached**:
        * Publish `SlaBreachedEvent` via EventPublisher.
        * Event handled by `AlertHandler` → saves breach record (idempotent) and sends alerts (with retry/circuit logic).
    * **Else (no breach)**:
        * Publish `RunCompletedEvent` → triggers cache eviction or warming downstream.
8. **Service returns RunResponse to Controller**
9. **Controller responds to Airflow / External System**
    * Returns HTTP `200 OK`.


#### Sequence Diagram

```mermaid
sequenceDiagram
    participant Airflow/ExternalSystem
    participant Controller
    participant Service
    participant Repository
    participant RedisCache
    participant EventPublisher
    participant AlertHandler

    Airflow/ExternalSystem->>Controller: POST /runs/{runId}/complete
    Note right of Controller: Auth via Azure AD
    Controller->>Service: completeRun(runId, request)
    Service->>Repository: findRecentRun(runId)
    Service->>Service: evaluate SLA (internal logic)
    Service->>Repository: upsert(run)

    Service->>RedisCache: update cache + deregister from SLA

    alt SLA Breached
        Service->>EventPublisher: publish(SlaBreachedEvent)
        EventPublisher->>AlertHandler: handleSlaBreachEvent
        AlertHandler->>AlertHandler: save breach (idempotent)
        AlertHandler->>AlertHandler: send alert (retry/circuit)
    else No Breach
        Service->>EventPublisher: publish(RunCompletedEvent)
    end

    Service-->>Controller: RunResponse
    Controller-->>Airflow/ExternalSystem: 200 OK


```

---
### Query Flow: Get Status
1. UI calls GET /status.
2. Service checks Redis response cache.
3. On miss: Query DB with partition pruning
4. Build response, cache it.

#### Flow Diagram

```mermaid
flowchart TD
    A[UI Request] --> B[Controller]
    B --> C[Service: getCalculatorStatus]
    C --> D{Redis Hit?}
    D -- Yes --> E[Return Cached Response]
    D -- No --> F["Repository: findRecentRuns (partitioned)"]
    F --> G[Build Response]
    G --> H[Cache Response in Redis]
    H --> I[Return Response]
```

### Query Flow: Batch Status (multiple calculators)
Similar to single, but with batch cache gets/sets and batch DB queries.

#### Sequence Diagram

```mermaid
sequenceDiagram
    participant UI
    participant Controller
    participant Service
    participant RedisCache
    participant Repository

    UI->>Controller: POST /batch/status (calculatorIds)
    Controller->>Service: getBatchCalculatorStatus
    Service->>RedisCache: getBatchStatusResponses
    Service->>Repository: findBatchRecentRuns (misses only)
    Service->>RedisCache: cacheBatchStatusResponses (fresh)
    Service-->>Controller: List<CalculatorStatusResponse>
```

## SLA Monitoring
- Scheduled: Every 2 min, check Redis ZSET for breached runs.
- Get breached/approaching runs → Publish events → Alert.

### Flow Diagram

```mermaid
flowchart TD
    A[Scheduled Task] --> B[SlaMonitoringCache: getBreachedRuns]
    B --> C[For each: Publish SlaBreachedEvent]
    C --> D[AlertHandler: Handle Event]
    D --> E["Save Breach"]
    E --> F["Send Alert (retry/circuit)"]
```
### Run Lifecycle State Machine
```mermaid
stateDiagram-v2
    [*] --> RUNNING : startRun

    RUNNING --> SUCCESS : completeRun(success)
    RUNNING --> FAILED : completeRun(failure)

    RUNNING --> SLA_BREACHED : slaDeadlineExceeded
    SLA_BREACHED --> SUCCESS : completeRun(success)
    SLA_BREACHED --> FAILED : completeRun(failure)

    SUCCESS --> [*]
    FAILED --> [*]

    note right of RUNNING
      Persisted immediately
      Cached with short TTL
      Registered for SLA tracking
    end note

    note right of SLA_BREACHED
      Detected by scheduler
    end note
```

### Caching Flow
- **Write-Through**: On DB write, update Redis sorted set (recent runs), track running.
- **Read-Through**: Check Redis first; on miss, query DB, cache response.
- **Eviction**: On events (start/complete/breach), evict response cache.
- **Warming**: On complete, proactively query and cache recent runs.
- **Batch**: Pipelined Redis operations.
- **TTL**: Dynamic based on status (short for RUNNING, longer for completed).
- **Bloom Filter**: Quick existence checks.

#### Flow Diagram
```mermaid
flowchart TD
    A[DB Write] --> B["Cache Write-Through (ZSET)"]
    C[Query] --> D{Response Cache Hit?}
    D -- Yes --> E[Return]
    D -- No --> F[Recent Runs ZSET Hit?]
    F -- Yes --> G[Build Response]
    F -- No --> H[DB Query]
    H --> I[Cache Recent Runs]
    I --> G
    G --> J[Cache Full Response]
    J --> E
```

## Caching Strategy

- **Structures**:
    - Sorted Set: Recent runs (score: timestamp), TTL dynamic.
    - Value: Full responses, TTL 30-60s.
    - Set: Running calculators.
    - Bloom: Existence checks.
- **SLA Monitoring**: ZSET (score: SLA deadline), Hash for info.
- **Eviction**: Explicit on changes.
- **Batch**: Pipelined for performance.

### Key Design

| Key                           | Type | Purpose                       |
| ----------------------------- | ---- | ----------------------------- |
| obs:runs:zset:{calc}:{tenant} | ZSET | Recent runs (time-ordered)    |
| obs:status:{calc}:{tenant}    | KV   | Full status response          |
| obs:running                   | SET  | Currently running calculators |
| obs:active:bloom              | SET  | Existence filter              |
| obs:sla:deadlines             | ZSET | SLA deadlines                 |
| obs:sla:run_info              | HASH | Minimal run metadata          |


## Breach Detection
```mermaid
sequenceDiagram
    participant Scheduler
    participant Redis
    participant API
    participant Postgres

    Scheduler->>Redis: ZRANGEBYSCORE sla_deadlines <= now
    Redis-->>Scheduler: breached runIds
    Scheduler->>API: mark SLA breached
    API->>Postgres: update slaBreached=true
    API->>Redis: update status cache
```


## Security
- **Auth**: Support both password and AAD RBAC

## General Guidelines:
- **Tracing**: OpenTelemetry (Azure Logging).
- **Logging**
- **Scheduling**: Partition creation
- **Resilience**: Exponential backoff Retry/circuit for alerting
- **Migration**: Flyway for schema
- Follow codebase patterns (e.g., Lombok, Optional for nulls).

