Problem Statement:
You are an expert Software Architect and senior developer with deep knowledge of distributed systems, orchestration frameworks, Java, Spring, Database, Python, Apache Airflow, and latest opensource frameworks. I want you to help design an Observability backend service. It will act as a single central source for 
calculator runtime informations like SLA, start time, end time, run parameters, run status. It will expose the data as REST APIs for other systems to read and use the information.
Existing infra in the team: Airflow , Azure PostgreSQL Flexible Server (primary store), Redis (cache), in-house UI (consumer), AKS (deployment).
Cloud & infra: Azure-first architecture (AKS, ACR, Azure AD, Key Vault, Azure Monitor).
Goal:
Input to the Observability Service:
 - There will be REST endpoints specifically to take in obeservability informations
 - These informations will be then stored in a postgresql database tables
 
 
Output of Observability Service:
 - Various REST endpoints will be exposed for other services to fetch relevant obeservability data
 
 
The Observability Service will not capture the logs, rather key calculator runtime informations like start time, end time, duration, run parameters, run status, 
SLA etc.
Key features:
- Efficient storage for high-cardinality writes
- Query APIs:
Run lookup: /runs/{runId}
Query runs: /runs?calculator=...&from=...&to=...&status=...
Aggregations/SLOs: /slo/{calculator}/summary?window=30d
Latest SLA violations: /alerts/sla
Example REST API surface (OpenAPI style)
GET /v1/runs/{runId} — full event + enriched context
GET /v1/runs — filter by calculatorId, tenantId, time range, status, tags; support pagination & sorting
GET /v1/calculators/{id}/slo — computed SLO/SLI statistics (p95, error-rate, SLA breach count)
- SLO / SLI management: define targets, calculate burn rates, and store historic SLI data.
- Multi-tenancy & RBAC (scoped queries, tenant isolation).
Live SLO/SLA evaluation
Compute:
- success rate
- SLA breaches by calculator run
- average duration of each calculator run over a time period (30 days)
- average zoned clock time (CET) of calculator run start and end times over a time period (30 days)
Integration with OpenTelemetry do directly send SLA breach alerts to Azure 
Help design a robust Observability Backend Service using Java, Spring Boot and any related opensource frameworks. Consider the current technology stack the team is comfortable with.

# Design1 by Claude:

┌─────────────────────────────────────────────────────────────┐
│                     API Gateway Layer                        │
│              (Spring Cloud Gateway - Optional)               │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                  Observability Service (AKS)                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         Ingestion API Layer (Write Path)             │  │
│  │  - High throughput writes                            │  │
│  │  - Async processing via queue                        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │          Query API Layer (Read Path)                 │  │
│  │  - Redis caching                                     │  │
│  │  - Efficient queries with indexes                    │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │        SLO/SLA Evaluation Engine                     │  │
│  │  - Scheduled computations                            │  │
│  │  - Real-time breach detection                        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         OpenTelemetry Integration                    │  │
│  │  - Metrics export                                    │  │
│  │  - Alert publishing                                  │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
           ↓                    ↓                    ↓
    ┌──────────┐         ┌──────────┐        ┌─────────────┐
    │PostgreSQL│         │  Redis   │        │Azure Monitor│
    │ Flexible │         │  Cache   │        │   + OTel    │
    └──────────┘         └──────────┘        └─────────────┘


# Observability Backend Service - Comprehensive Design

I'll design a production-ready observability service that aligns with your Azure-first architecture and team's expertise.

## 1. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     API Gateway Layer                        │
│              (Spring Cloud Gateway - Optional)               │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                  Observability Service (AKS)                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         Ingestion API Layer (Write Path)             │  │
│  │  - High throughput writes                            │  │
│  │  - Async processing via queue                        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │          Query API Layer (Read Path)                 │  │
│  │  - Redis caching                                     │  │
│  │  - Efficient queries with indexes                    │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │        SLO/SLA Evaluation Engine                     │  │
│  │  - Scheduled computations                            │  │
│  │  - Real-time breach detection                        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         OpenTelemetry Integration                    │  │
│  │  - Metrics export                                    │  │
│  │  - Alert publishing                                  │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
           ↓                    ↓                    ↓
    ┌──────────┐         ┌──────────┐        ┌─────────────┐
    │PostgreSQL│         │  Redis   │        │Azure Monitor│
    │ Flexible │         │  Cache   │        │   + OTel    │
    └──────────┘         └──────────┘        └─────────────┘
```

## 2. Technology Stack

### Core Framework
- **Spring Boot 3.2+** (Java 17+)
- **Spring Data JPA** with Hibernate
- **Spring Cache** (Redis)
- **Spring Security** (Azure AD integration)
- **Spring Actuator** (health checks, metrics)

### Database & Caching
- **PostgreSQL** (Azure PostgreSQL Flexible Server) with partitioning
- **Redis** (Azure Cache for Redis) for query caching
- **HikariCP** for connection pooling

### API & Documentation
- **Spring REST** with OpenAPI 3.0
- **SpringDoc OpenAPI** (swagger-ui)
- **Jackson** for JSON processing
- **Bean Validation** (JSR-380)

### Observability & Monitoring
- **Micrometer** (metrics facade)
- **OpenTelemetry Java Agent** (traces, metrics, logs)
- **Azure Monitor OpenTelemetry Distro** for Azure integration
- **Prometheus** format metrics endpoint

### Async Processing
- **Spring AMQP** (if using RabbitMQ/Azure Service Bus)
- **Spring Kafka** (if using Kafka)
- **@Async** with ThreadPoolTaskExecutor

### Security & Multi-tenancy
- **Spring Security OAuth2** (Azure AD)
- **Azure Key Vault** integration for secrets
- **Tenant Context** (custom implementation)

### Scheduling & SLO Computation
- **Spring Scheduler** or **Quartz** for periodic jobs
- **Spring Batch** (optional, for heavy aggregations)

### Testing
- **JUnit 5**, **Mockito**, **TestContainers** (PostgreSQL)

---

## 3. Database Schema Design

### Core Tables

```sql
-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_partman";

-- 1. Calculator Runs (Partitioned by created_at - monthly)
CREATE TABLE calculator_runs (
    run_id VARCHAR(100) PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    
    -- Timing
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    duration_ms BIGINT,
    
    -- Status
    status VARCHAR(20) NOT NULL, -- RUNNING, SUCCESS, FAILED, TIMEOUT
    
    -- Run context
    run_parameters JSONB,
    tags JSONB, -- {env: "prod", region: "us-east"}
    
    -- Metadata
    triggered_by VARCHAR(100),
    version VARCHAR(50),
    
    -- SLA tracking
    expected_duration_ms BIGINT,
    sla_breached BOOLEAN DEFAULT FALSE,
    
    -- Audit
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Indexes
    CONSTRAINT chk_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED'))
) PARTITION BY RANGE (created_at);

-- Partitions (create via migration or automation)
CREATE TABLE calculator_runs_2025_01 PARTITION OF calculator_runs
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- Indexes
CREATE INDEX idx_runs_calculator_time ON calculator_runs(calculator_id, created_at DESC);
CREATE INDEX idx_runs_tenant_time ON calculator_runs(tenant_id, created_at DESC);
CREATE INDEX idx_runs_status ON calculator_runs(status) WHERE status != 'SUCCESS';
CREATE INDEX idx_runs_sla_breach ON calculator_runs(sla_breached, calculator_id) WHERE sla_breached = TRUE;
CREATE INDEX idx_runs_tags ON calculator_runs USING GIN(tags);

-- 2. Calculator Definitions
CREATE TABLE calculators (
    calculator_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    
    -- SLA configuration
    sla_target_duration_ms BIGINT,
    sla_target_success_rate DECIMAL(5,2), -- e.g., 99.50
    
    -- Metadata
    owner_team VARCHAR(100),
    tags JSONB,
    
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_calculators_active ON calculators(active) WHERE active = TRUE;

-- 3. SLO Definitions
CREATE TABLE slo_definitions (
    slo_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    calculator_id VARCHAR(100) REFERENCES calculators(calculator_id),
    tenant_id VARCHAR(50),
    
    -- SLO type: latency, availability, throughput
    slo_type VARCHAR(20) NOT NULL,
    
    -- Target
    target_value DECIMAL(10,2) NOT NULL, -- e.g., 95.00 for 95% success rate
    target_unit VARCHAR(20), -- percentage, milliseconds
    
    -- Time window
    window_duration VARCHAR(20) NOT NULL, -- 30d, 7d, 24h
    
    -- Error budget
    error_budget_remaining DECIMAL(10,2),
    
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT chk_slo_type CHECK (slo_type IN ('latency_p95', 'latency_p99', 'success_rate', 'throughput'))
);

CREATE INDEX idx_slo_calculator ON slo_definitions(calculator_id, active);

-- 4. SLI (Service Level Indicator) History - Pre-computed metrics
CREATE TABLE sli_history (
    sli_id BIGSERIAL PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50),
    
    -- Time bucket
    time_bucket TIMESTAMPTZ NOT NULL,
    bucket_duration VARCHAR(20) NOT NULL, -- 1h, 1d
    
    -- Metrics
    total_runs INTEGER NOT NULL,
    successful_runs INTEGER NOT NULL,
    failed_runs INTEGER NOT NULL,
    avg_duration_ms BIGINT,
    p95_duration_ms BIGINT,
    p99_duration_ms BIGINT,
    
    -- SLA breaches
    sla_breaches INTEGER DEFAULT 0,
    
    -- Clock time statistics (for zoned time analysis)
    avg_start_hour_cet DECIMAL(5,2),
    avg_end_hour_cet DECIMAL(5,2),
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(calculator_id, tenant_id, time_bucket, bucket_duration)
);

CREATE INDEX idx_sli_calculator_time ON sli_history(calculator_id, time_bucket DESC);
CREATE INDEX idx_sli_bucket ON sli_history(bucket_duration, time_bucket DESC);

-- 5. SLA Breach Events (for alerting)
CREATE TABLE sla_breach_events (
    breach_id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) REFERENCES calculator_runs(run_id),
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50),
    
    breach_type VARCHAR(50) NOT NULL, -- DURATION_EXCEEDED, FAILED_RUN, TIMEOUT
    
    expected_value BIGINT,
    actual_value BIGINT,
    
    severity VARCHAR(20) DEFAULT 'MEDIUM', -- LOW, MEDIUM, HIGH, CRITICAL
    
    alerted BOOLEAN DEFAULT FALSE,
    alerted_at TIMESTAMPTZ,
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_breach_calculator_time ON sla_breach_events(calculator_id, created_at DESC);
CREATE INDEX idx_breach_alerted ON sla_breach_events(alerted) WHERE alerted = FALSE;

-- 6. Tenants (for multi-tenancy)
CREATE TABLE tenants (
    tenant_id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    
    active BOOLEAN DEFAULT TRUE,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 4. Core Domain Model (Java)

```java
// Entity: CalculatorRun.java
@Entity
@Table(name = "calculator_runs", indexes = {
    @Index(name = "idx_runs_calculator_time", columnList = "calculator_id,created_at"),
    @Index(name = "idx_runs_tenant_time", columnList = "tenant_id,created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class CalculatorRun {
    
    @Id
    @Column(name = "run_id", length = 100)
    private String runId;
    
    @Column(name = "calculator_id", nullable = false, length = 100)
    private String calculatorId;
    
    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;
    
    @Column(name = "start_time", nullable = false)
    private Instant startTime;
    
    @Column(name = "end_time")
    private Instant endTime;
    
    @Column(name = "duration_ms")
    private Long durationMs;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RunStatus status;
    
    @Type(JsonBinaryType.class)
    @Column(name = "run_parameters", columnDefinition = "jsonb")
    private Map<String, Object> runParameters;
    
    @Type(JsonBinaryType.class)
    @Column(name = "tags", columnDefinition = "jsonb")
    private Map<String, String> tags;
    
    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;
    
    @Column(name = "version", length = 50)
    private String version;
    
    @Column(name = "expected_duration_ms")
    private Long expectedDurationMs;
    
    @Column(name = "sla_breached")
    private Boolean slaBreached = false;
    
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    // Getters, setters, builder
}

// Enum: RunStatus.java
public enum RunStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED
}

// Entity: Calculator.java
@Entity
@Table(name = "calculators")
public class Calculator {
    
    @Id
    @Column(name = "calculator_id", length = 100)
    private String calculatorId;
    
    @Column(nullable = false, length = 200)
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "sla_target_duration_ms")
    private Long slaTargetDurationMs;
    
    @Column(name = "sla_target_success_rate", precision = 5, scale = 2)
    private BigDecimal slaTargetSuccessRate;
    
    @Column(name = "owner_team", length = 100)
    private String ownerTeam;
    
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> tags;
    
    @Column(nullable = false)
    private Boolean active = true;
    
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    // Getters, setters
}

// Entity: SloDefinition.java
@Entity
@Table(name = "slo_definitions")
public class SloDefinition {
    
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "slo_id")
    private UUID sloId;
    
    @Column(name = "calculator_id", length = 100)
    private String calculatorId;
    
    @Column(name = "tenant_id", length = 50)
    private String tenantId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "slo_type", nullable = false, length = 20)
    private SloType sloType;
    
    @Column(name = "target_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal targetValue;
    
    @Column(name = "target_unit", length = 20)
    private String targetUnit;
    
    @Column(name = "window_duration", nullable = false, length = 20)
    private String windowDuration; // e.g., "30d", "7d"
    
    @Column(name = "error_budget_remaining", precision = 10, scale = 2)
    private BigDecimal errorBudgetRemaining;
    
    @Column(nullable = false)
    private Boolean active = true;
    
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    // Getters, setters
}

public enum SloType {
    LATENCY_P95,
    LATENCY_P99,
    SUCCESS_RATE,
    THROUGHPUT
}

// Entity: SliHistory.java
@Entity
@Table(name = "sli_history")
public class SliHistory {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sli_id")
    private Long sliId;
    
    @Column(name = "calculator_id", nullable = false, length = 100)
    private String calculatorId;
    
    @Column(name = "tenant_id", length = 50)
    private String tenantId;
    
    @Column(name = "time_bucket", nullable = false)
    private Instant timeBucket;
    
    @Column(name = "bucket_duration", nullable = false, length = 20)
    private String bucketDuration;
    
    @Column(name = "total_runs", nullable = false)
    private Integer totalRuns;
    
    @Column(name = "successful_runs", nullable = false)
    private Integer successfulRuns;
    
    @Column(name = "failed_runs", nullable = false)
    private Integer failedRuns;
    
    @Column(name = "avg_duration_ms")
    private Long avgDurationMs;
    
    @Column(name = "p95_duration_ms")
    private Long p95DurationMs;
    
    @Column(name = "p99_duration_ms")
    private Long p99DurationMs;
    
    @Column(name = "sla_breaches")
    private Integer slaBreaches = 0;
    
    @Column(name = "avg_start_hour_cet", precision = 5, scale = 2)
    private BigDecimal avgStartHourCet;
    
    @Column(name = "avg_end_hour_cet", precision = 5, scale = 2)
    private BigDecimal avgEndHourCet;
    
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    // Getters, setters
}

// Entity: SlaBreachEvent.java
@Entity
@Table(name = "sla_breach_events")
public class SlaBreachEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "breach_id")
    private Long breachId;
    
    @Column(name = "run_id", length = 100)
    private String runId;
    
    @Column(name = "calculator_id", nullable = false, length = 100)
    private String calculatorId;
    
    @Column(name = "tenant_id", length = 50)
    private String tenantId;
    
    @Column(name = "breach_type", nullable = false, length = 50)
    private String breachType;
    
    @Column(name = "expected_value")
    private Long expectedValue;
    
    @Column(name = "actual_value")
    private Long actualValue;
    
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Severity severity = Severity.MEDIUM;
    
    @Column(nullable = false)
    private Boolean alerted = false;
    
    @Column(name = "alerted_at")
    private Instant alertedAt;
    
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    // Getters, setters
}

public enum Severity {
    LOW, MEDIUM, HIGH, CRITICAL
}
```

---

## 5. API Layer Design

### 5.1 Ingestion API (Write Path)

```java
// Controller: RunIngestionController.java
@RestController
@RequestMapping("/api/v1/runs")
@Validated
@Tag(name = "Run Ingestion", description = "APIs for ingesting calculator run data")
public class RunIngestionController {
    
    private final RunIngestionService runIngestionService;
    
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new calculator run")
    public ResponseEntity<RunResponse> createRun(
            @Valid @RequestBody CreateRunRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        CalculatorRun run = runIngestionService.createRun(request, tenantId);
        return ResponseEntity.created(
            URI.create("/api/v1/runs/" + run.getRunId()))
            .body(RunResponse.from(run));
    }
    
    @PutMapping("/{runId}")
    @Operation(summary = "Update an existing calculator run")
    public ResponseEntity<RunResponse> updateRun(
            @PathVariable String runId,
            @Valid @RequestBody UpdateRunRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        CalculatorRun run = runIngestionService.updateRun(runId, request, tenantId);
        return ResponseEntity.ok(RunResponse.from(run));
    }
    
    @PostMapping("/{runId}/complete")
    @Operation(summary = "Mark a run as completed")
    public ResponseEntity<RunResponse> completeRun(
            @PathVariable String runId,
            @Valid @RequestBody CompleteRunRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        CalculatorRun run = runIngestionService.completeRun(runId, request, tenantId);
        return ResponseEntity.ok(RunResponse.from(run));
    }
    
    @PostMapping("/{runId}/fail")
    @Operation(summary = "Mark a run as failed")
    public ResponseEntity<RunResponse> failRun(
            @PathVariable String runId,
            @Valid @RequestBody FailRunRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        CalculatorRun run = runIngestionService.failRun(runId, request, tenantId);
        return ResponseEntity.ok(RunResponse.from(run));
    }
}

// DTOs for ingestion
@Data
@Builder
public class CreateRunRequest {
    
    @NotBlank
    private String runId;
    
    @NotBlank
    private String calculatorId;
    
    @NotNull
    private Instant startTime;
    
    private Map<String, Object> runParameters;
    private Map<String, String> tags;
    private String triggeredBy;
    private String version;
    private Long expectedDurationMs;
}

@Data
public class UpdateRunRequest {
    private RunStatus status;
    private Map<String, Object> runParameters;
    private Map<String, String> tags;
}

@Data
public class CompleteRunRequest {
    @NotNull
    private Instant endTime;
    
    private Map<String, Object> additionalMetadata;
}

@Data
public class FailRunRequest {
    @NotNull
    private Instant endTime;
    
    private String errorMessage;
    private String errorCode;
}

@Data
@Builder
public class RunResponse {
    private String runId;
    private String calculatorId;
    private String tenantId;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private RunStatus status;
    private Map<String, Object> runParameters;
    private Map<String, String> tags;
    private Boolean slaBreached;
    private Instant createdAt;
    private Instant updatedAt;
    
    public static RunResponse from(CalculatorRun run) {
        return RunResponse.builder()
            .runId(run.getRunId())
            .calculatorId(run.getCalculatorId())
            .tenantId(run.getTenantId())
            .startTime(run.getStartTime())
            .endTime(run.getEndTime())
            .durationMs(run.getDurationMs())
            .status(run.getStatus())
            .runParameters(run.getRunParameters())
            .tags(run.getTags())
            .slaBreached(run.getSlaBreached())
            .createdAt(run.getCreatedAt())
            .updatedAt(run.getUpdatedAt())
            .build();
    }
}
```

### 5.2 Query API (Read Path)

```java
// Controller: RunQueryController.java
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Query", description = "APIs for querying calculator runs")
public class RunQueryController {
    
    private final RunQueryService runQueryService;
    
    @GetMapping("/{runId}")
    @Cacheable(value = "runs", key = "#runId")
    @Operation(summary = "Get a calculator run by ID")
    public ResponseEntity<RunDetailResponse> getRunById(
            @PathVariable String runId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        RunDetailResponse response = runQueryService.getRunById(runId, tenantId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping
    @Operation(summary = "Query calculator runs with filters")
    public ResponseEntity<Page<RunResponse>> queryRuns(
            @RequestParam(required = false) String calculatorId,
            @RequestParam(required = false) RunStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Map<String, String> tags,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        RunQueryCriteria criteria = RunQueryCriteria.builder()
            .tenantId(tenantId)
            .calculatorId(calculatorId)
            .status(status)
            .from(from)
            .to(to)
            .tags(tags)
            .build();
        
        Pageable pageable = PageRequest.of(page, size, parseSortParam(sort));
        Page<RunResponse> runs = runQueryService.queryRuns(criteria, pageable);
        
        return ResponseEntity.ok(runs);
    }
    
    @GetMapping("/recent")
    @Cacheable(value = "recentRuns", key = "#tenantId + '-' + #calculatorId + '-' + #limit")
    @Operation(summary = "Get recent runs for a calculator")
    public ResponseEntity<List<RunResponse>> getRecentRuns(
            @RequestParam String calculatorId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        List<RunResponse> runs = runQueryService.getRecentRuns(calculatorId, tenantId, limit);
        return ResponseEntity.ok(runs);
    }
    
    private Sort parseSortParam(String sort) {
        String[] parts = sort.split(",");
        return Sort.by(
            parts.length > 1 && parts[1].equalsIgnoreCase("asc") 
                ? Sort.Direction.ASC 
                : Sort.Direction.DESC,
            parts[0]
        );
    }
}

// Controller: SloController.java
@RestController
@RequestMapping("/api/v1/slo")
@Tag(name = "SLO Management", description = "Service Level Objective APIs")
public class SloController {
    
    private final SloService sloService;
    
    @GetMapping("/calculators/{calculatorId}")
    @Cacheable(value = "sloSummary", key = "#calculatorId + '-' + #window + '-' + #tenantId")
    @Operation(summary = "Get SLO summary for a calculator")
    public ResponseEntity<SloSummaryResponse> getSloSummary(
            @PathVariable String calculatorId,
            @RequestParam(defaultValue = "30d") String window,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        SloSummaryResponse summary = sloService.getSloSummary(calculatorId, tenantId, window);
        return ResponseEntity.ok(summary);
    }
    
    @GetMapping("/calculators/{calculatorId}/burn-rate")
    @Operation(summary = "Get error budget burn rate")
    public ResponseEntity<BurnRateResponse> getBurnRate(
            @PathVariable String calculatorId,
            @RequestParam(defaultValue = "7d") String window,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        BurnRateResponse burnRate = sloService.calculateBurnRate(calculatorId, tenantId, window);
        return ResponseEntity.ok(burnRate);
    }
    
    @GetMapping("/breach-history")
    @Operation(summary = "Get SLA breach history")
    public ResponseEntity<Page<SlaBreachResponse>> getBreachHistory(
            @RequestParam(required = false) String calculatorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<SlaBreachResponse> breaches = sloService.getBreachHistory(
            calculatorId, tenantId, from, to, pageable);
        
        return ResponseEntity.ok(breaches);
    }
}

// Controller: AlertController.java
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "SLA breach alerts")
public class AlertController {
    
    private final AlertService alertService;
    
    @GetMapping("/sla")
    @Operation(summary = "Get latest SLA violations")
    public ResponseEntity<List<SlaBreachResponse>> getLatestSlaViolations(
            @RequestParam(defaultValue = "24h") String window,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        List<SlaBreachResponse> violations = alertService.getRecentViolations(tenantId, window);
        return ResponseEntity.ok(violations);
    }
    
    @GetMapping("/active")
    @Operation(summary = "Get active alerts that haven't been acknowledged")
    public ResponseEntity<List<SlaBreachResponse>> getActiveAlerts(
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        List<SlaBreachResponse> alerts = alertService.getActiveAlerts(tenantId);
        return ResponseEntity.ok(alerts);
    }
}

// DTOs for queries
@Data
@Builder
public class RunQueryCriteria {
    private String tenantId;
    private String calculatorId;
    private RunStatus status;
    private Instant from;
    private Instant to;
    private Map<String, String> tags;
}

@Data
@Builder
public class RunDetailResponse {
    private String runId;
    private String calculatorId;
    private String tenantId;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private RunStatus status;
    private Map<String, Object> runParameters;
    private Map<String, String> tags;
    private String triggeredBy;
    private String version;
    private Boolean slaBreached;
    private Long expectedDurationMs;
    private Instant createdAt;
    private Instant updatedAt;
    
    // Enriched context
    private CalculatorInfo calculatorInfo;
    private SlaComplianceInfo slaComplianceInfo;
}

@Data
@Builder
public class SloSummaryResponse {
    private String calculatorId;
    private String window;
    
    // Success rate
    private BigDecimal successRate;
    private BigDecimal targetSuccessRate;
    
    // Latency
    private Long avgDurationMs;
    private Long p95DurationMs;
    private Long p99DurationMs;
    private Long targetDurationMs;
    
    // Counts
    private Integer totalRuns;
    private Integer successfulRuns;
    private Integer failedRuns;
    private Integer slaBreaches;
    
    // Clock time analysis (CET)
    private BigDecimal avgStartHourCet;
    private BigDecimal avgEndHourCet;
    
    // Error budget
    private BigDecimal errorBudgetRemaining;
    private BigDecimal errorBudgetConsumed;
}

@Data
@Builder
public class BurnRateResponse {
    private String calculatorId;
    private String window;
    private BigDecimal currentBurnRate;
    private BigDecimal allowedBurnRate;
    private String severity; // OK, WARNING, CRITICAL
}

@Data
@Builder
public class SlaBreachResponse {
    private Long breachId;
    private String runId;
    private String calculatorId;
    private String breachType;
    private Long expectedValue;
    private Long actualValue;
    private Severity severity;
    private Boolean alerted;
    private Instant createdAt;
}
```

---

## 6. Service Layer Implementation

### 6.1 Run Ingestion Service

```java
@Service
@Slf4j
public class RunIngestionService {
    
    private final CalculatorRunRepository runRepository;
    private final CalculatorRepository calculatorRepository;
    private final SlaEvaluationService slaEvaluationService;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantContext tenantContext;
    
    @Transactional
    public CalculatorRun createRun(CreateRunRequest request, String tenantId) {
        log.info("Creating run {} for calculator {} in tenant {}", 
            request.getRunId(), request.getCalculatorId(), tenantId);
        
        // Validate calculator exists
        Calculator calculator = calculatorRepository.findById(request.getCalculatorId())
            .orElseThrow(() -> new CalculatorNotFoundException(request.getCalculatorId()));
        
        CalculatorRun run = CalculatorRun.builder()
            .runId(request.getRunId())
            .calculatorId(request.getCalculatorId())
            .tenantId(tenantId)
            .startTime(request.getStartTime())
            .status(RunStatus.RUNNING)
            .runParameters(request.getRunParameters())
            .tags(request.getTags())
            .triggeredBy(request.getTriggeredBy())
            .version(request.getVersion())
            .expectedDurationMs(request.getExpectedDurationMs() != null 
                ? request.getExpectedDurationMs() 
                : calculator.getSlaTargetDurationMs())
            .build();
        
        CalculatorRun savedRun = runRepository.save(run);
        
        // Publish event for async processing
        eventPublisher.publishEvent(new RunCreatedEvent(savedRun));
        
        return savedRun;
    }
    
    @Transactional
    public CalculatorRun updateRun(String runId, UpdateRunRequest request, String tenantId) {
        CalculatorRun run = findRunWithTenantCheck(runId, tenantId);
        
        if (request.getStatus() != null) {
            run.setStatus(request.getStatus());
        }
        if (request.getRunParameters() != null) {
            run.setRunParameters(request.getRunParameters());
        }
        if (request.getTags() != null) {
            run.setTags(request.getTags());
        }
        
        return runRepository.save(run);
    }
    
    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        CalculatorRun run = findRunWithTenantCheck(runId, tenantId);
        
        run.setEndTime(request.getEndTime());
        run.setStatus(RunStatus.SUCCESS);
        run.setDurationMs(Duration.between(run.getStartTime(), request.getEndTime()).toMillis());
        
        // Check for SLA breach
        boolean breached = slaEvaluationService.evaluateSla(run);
        run.setSlaBreached(breached);
        
        CalculatorRun savedRun = runRepository.save(run);
        
        // Publish completion event
        eventPublisher.publishEvent(new RunCompletedEvent(savedRun, breached));
        
        return savedRun;
    }
    
    @Transactional
    public CalculatorRun failRun(String runId, FailRunRequest request, String tenantId) {
        CalculatorRun run = findRunWithTenantCheck(runId, tenantId);
        
        run.setEndTime(request.getEndTime());
        run.setStatus(RunStatus.FAILED);
        run.setDurationMs(Duration.between(run.getStartTime(), request.getEndTime()).toMillis());
        run.setSlaBreached(true); // Failed runs always breach SLA
        
        CalculatorRun savedRun = runRepository.save(run);
        
        // Create breach event
        slaEvaluationService.recordBreach(savedRun, "FAILED_RUN");
        
        // Publish failure event
        eventPublisher.publishEvent(new RunFailedEvent(savedRun));
        
        return savedRun;
    }
    
    private CalculatorRun findRunWithTenantCheck(String runId, String tenantId) {
        CalculatorRun run = runRepository.findById(runId)
            .orElseThrow(() -> new RunNotFoundException(runId));
        
        if (!run.getTenantId().equals(tenantId)) {
            throw new TenantAccessDeniedException(tenantId, runId);
        }
        
        return run;
    }
}
```

### 6.2 SLA Evaluation Service

```java
@Service
@Slf4j
public class SlaEvaluationService {
    
    private final CalculatorRepository calculatorRepository;
    private final SloDefinitionRepository sloRepository;
    private final SlaBreachEventRepository breachRepository;
    private final OpenTelemetryService openTelemetryService;
    
    public boolean evaluateSla(CalculatorRun run) {
        Calculator calculator = calculatorRepository.findById(run.getCalculatorId())
            .orElseThrow(() -> new CalculatorNotFoundException(run.getCalculatorId()));
        
        boolean breached = false;
        
        // Check duration-based SLA
        if (calculator.getSlaTargetDurationMs() != null && run.getDurationMs() != null) {
            if (run.getDurationMs() > calculator.getSlaTargetDurationMs()) {
                recordBreach(run, "DURATION_EXCEEDED");
                breached = true;
            }
        }
        
        // Check custom SLOs
        List<SloDefinition> slos = sloRepository.findByCalculatorIdAndActive(
            run.getCalculatorId(), true);
        
        for (SloDefinition slo : slos) {
            if (evaluateSloDefinition(run, slo, calculator)) {
                breached = true;
            }
        }
        
        return breached;
    }
    
    private boolean evaluateSloDefinition(CalculatorRun run, SloDefinition slo, Calculator calculator) {
        // Implement specific SLO evaluation logic based on SLO type
        // This is a simplified version
        return false;
    }
    
    @Transactional
    public void recordBreach(CalculatorRun run, String breachType) {
        log.warn("SLA breach detected for run {} of type {}", run.getRunId(), breachType);
        
        SlaBreachEvent breach = new SlaBreachEvent();
        breach.setRunId(run.getRunId());
        breach.setCalculatorId(run.getCalculatorId());
        breach.setTenantId(run.getTenantId());
        breach.setBreachType(breachType);
        breach.setExpectedValue(run.getExpectedDurationMs());
        breach.setActualValue(run.getDurationMs());
        breach.setSeverity(calculateSeverity(run, breachType));
        
        SlaBreachEvent savedBreach = breachRepository.save(breach);
        
        // Send alert via OpenTelemetry
        openTelemetryService.sendBreachAlert(savedBreach);
    }
    
    private Severity calculateSeverity(CalculatorRun run, String breachType) {
        if (breachType.equals("FAILED_RUN")) {
            return Severity.HIGH;
        }
        
        if (run.getExpectedDurationMs() != null && run.getDurationMs() != null) {
            double overage = (double) run.getDurationMs() / run.getExpectedDurationMs();
            if (overage > 2.0) return Severity.CRITICAL;
            if (overage > 1.5) return Severity.HIGH;
            if (overage > 1.2) return Severity.MEDIUM;
        }
        
        return Severity.LOW;
    }
}
```

### 6.3 SLO Service with Time-Window Calculations

```java
@Service
@Slf4j
public class SloService {
    
    private final SliHistoryRepository sliHistoryRepository;
    private final CalculatorRunRepository runRepository;
    private final CalculatorRepository calculatorRepository;
    private final SlaBreachEventRepository breachRepository;
    
    @Cacheable(value = "sloSummary", key = "#calculatorId + '-' + #window + '-' + #tenantId")
    public SloSummaryResponse getSloSummary(String calculatorId, String tenantId, String window) {
        Duration windowDuration = parseWindowDuration(window);
        Instant from = Instant.now().minus(windowDuration);
        
        Calculator calculator = calculatorRepository.findById(calculatorId)
            .orElseThrow(() -> new CalculatorNotFoundException(calculatorId));
        
        // Try to get from pre-computed SLI history first
        List<SliHistory> sliData = sliHistoryRepository.findByCalculatorIdAndTenantIdAndTimeBucketAfter(
            calculatorId, tenantId, from);
        
        if (!sliData.isEmpty()) {
            return buildSummaryFromSli(sliData, calculator, window);
        }
        
        // Fallback: compute on-demand from runs
        return computeSummaryFromRuns(calculatorId, tenantId, from, calculator, window);
    }
    
    private SloSummaryResponse buildSummaryFromSli(List<SliHistory> sliData, 
                                                     Calculator calculator, 
                                                     String window) {
        int totalRuns = sliData.stream().mapToInt(SliHistory::getTotalRuns).sum();
        int successfulRuns = sliData.stream().mapToInt(SliHistory::getSuccessfulRuns).sum();
        int failedRuns = sliData.stream().mapToInt(SliHistory::getFailedRuns).sum();
        int slaBreaches = sliData.stream().mapToInt(SliHistory::getSlaBreaches).sum();
        
        BigDecimal successRate = totalRuns > 0 
            ? BigDecimal.valueOf(successfulRuns).divide(
                BigDecimal.valueOf(totalRuns), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;
        
        // Weighted average for durations
        long totalDuration = sliData.stream()
            .mapToLong(s -> s.getAvgDurationMs() * s.getTotalRuns())
            .sum();
        long avgDuration = totalRuns > 0 ? totalDuration / totalRuns : 0;
        
        // P95/P99 approximation (take max from buckets)
        Long p95 = sliData.stream()
            .map(SliHistory::getP95DurationMs)
            .filter(Objects::nonNull)
            .max(Long::compareTo)
            .orElse(null);
        
        Long p99 = sliData.stream()
            .map(SliHistory::getP99DurationMs)
            .filter(Objects::nonNull)
            .max(Long::compareTo)
            .orElse(null);
        
        // Average clock times
        BigDecimal avgStartHour = sliData.stream()
            .map(SliHistory::getAvgStartHourCet)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(sliData.size()), 2, RoundingMode.HALF_UP);
        
        BigDecimal avgEndHour = sliData.stream()
            .map(SliHistory::getAvgEndHourCet)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(sliData.size()), 2, RoundingMode.HALF_UP);
        
        // Error budget calculation
        BigDecimal targetRate = calculator.getSlaTargetSuccessRate() != null 
            ? calculator.getSlaTargetSuccessRate() 
            : BigDecimal.valueOf(99.0);
        
        BigDecimal errorBudget = BigDecimal.valueOf(100).subtract(targetRate);
        BigDecimal errorRate = BigDecimal.valueOf(100).subtract(successRate);
        BigDecimal budgetConsumed = errorBudget.compareTo(BigDecimal.ZERO) > 0
            ? errorRate.divide(errorBudget, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;
        BigDecimal budgetRemaining = BigDecimal.valueOf(100).subtract(budgetConsumed);
        
        return SloSummaryResponse.builder()
            .calculatorId(calculator.getCalculatorId())
            .window(window)
            .successRate(successRate)
            .targetSuccessRate(targetRate)
            .avgDurationMs(avgDuration)
            .p95DurationMs(p95)
            .p99DurationMs(p99)
            .targetDurationMs(calculator.getSlaTargetDurationMs())
            .totalRuns(totalRuns)
            .successfulRuns(successfulRuns)
            .failedRuns(failedRuns)
            .slaBreaches(slaBreaches)
            .avgStartHourCet(avgStartHour)
            .avgEndHourCet(avgEndHour)
            .errorBudgetRemaining(budgetRemaining)
            .errorBudgetConsumed(budgetConsumed)
            .build();
    }
    
    private SloSummaryResponse computeSummaryFromRuns(String calculatorId, 
                                                       String tenantId, 
                                                       Instant from,
                                                       Calculator calculator,
                                                       String window) {
        List<CalculatorRun> runs = runRepository.findByCalculatorIdAndTenantIdAndCreatedAtAfter(
            calculatorId, tenantId, from);
        
        int totalRuns = runs.size();
        int successfulRuns = (int) runs.stream()
            .filter(r -> r.getStatus() == RunStatus.SUCCESS)
            .count();
        int failedRuns = (int) runs.stream()
            .filter(r -> r.getStatus() == RunStatus.FAILED)
            .count();
        int slaBreaches = (int) runs.stream()
            .filter(r -> Boolean.TRUE.equals(r.getSlaBreached()))
            .count();
        
        BigDecimal successRate = totalRuns > 0 
            ? BigDecimal.valueOf(successfulRuns * 100.0 / totalRuns).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        // Calculate percentiles
        List<Long> durations = runs.stream()
            .map(CalculatorRun::getDurationMs)
            .filter(Objects::nonNull)
            .sorted()
            .collect(Collectors.toList());
        
        Long avgDuration = durations.isEmpty() ? null 
            : (long) durations.stream().mapToLong(Long::longValue).average().orElse(0);
        Long p95 = calculatePercentile(durations, 95);
        Long p99 = calculatePercentile(durations, 99);
        
        // Clock time analysis (CET)
        ZoneId cetZone = ZoneId.of("CET");
        BigDecimal avgStartHour = runs.stream()
            .map(r -> ZonedDateTime.ofInstant(r.getStartTime(), cetZone).getHour() + 
                      ZonedDateTime.ofInstant(r.getStartTime(), cetZone).getMinute() / 60.0)
            .collect(Collectors.averagingDouble(Double::doubleValue));
        
        BigDecimal avgEndHour = runs.stream()
            .filter(r -> r.getEndTime() != null)
            .map(r -> ZonedDateTime.ofInstant(r.getEndTime(), cetZone).getHour() + 
                      ZonedDateTime.ofInstant(r.getEndTime(), cetZone).getMinute() / 60.0)
            .collect(Collectors.averagingDouble(Double::doubleValue));
        
        // Error budget
        BigDecimal targetRate = calculator.getSlaTargetSuccessRate() != null 
            ? calculator.getSlaTargetSuccessRate() 
            : BigDecimal.valueOf(99.0);
        
        BigDecimal errorBudget = BigDecimal.valueOf(100).subtract(targetRate);
        BigDecimal errorRate = BigDecimal.valueOf(100).subtract(successRate);
        BigDecimal budgetConsumed = errorBudget.compareTo(BigDecimal.ZERO) > 0
            ? errorRate.divide(errorBudget, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;
        
        return SloSummaryResponse.builder()
            .calculatorId(calculatorId)
            .window(window)
            .successRate(successRate)
            .targetSuccessRate(targetRate)
            .avgDurationMs(avgDuration)
            .p95DurationMs(p95)
            .p99DurationMs(p99)
            .targetDurationMs(calculator.getSlaTargetDurationMs())
            .totalRuns(totalRuns)
            .successfulRuns(successfulRuns)
            .failedRuns(failedRuns)
            .slaBreaches(slaBreaches)
            .avgStartHourCet(BigDecimal.valueOf(avgStartHour).setScale(2, RoundingMode.HALF_UP))
            .avgEndHourCet(BigDecimal.valueOf(avgEndHour).setScale(2, RoundingMode.HALF_UP))
            .errorBudgetRemaining(BigDecimal.valueOf(100).subtract(budgetConsumed))
            .errorBudgetConsumed(budgetConsumed)
            .build();
    }
    
    private Long calculatePercentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return null;
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
    
    private Duration parseWindowDuration(String window) {
        // Parse strings like "30d", "7d", "24h", "1h"
        String unit = window.substring(window.length() - 1);
        long value = Long.parseLong(window.substring(0, window.length() - 1));
        
        return switch (unit) {
            case "d" -> Duration.ofDays(value);
            case "h" -> Duration.ofHours(value);
            case "m" -> Duration.ofMinutes(value);
            default -> throw new IllegalArgumentException("Invalid window format: " + window);
        };
    }
    
    public BurnRateResponse calculateBurnRate(String calculatorId, String tenantId, String window) {
        Duration windowDuration = parseWindowDuration(window);
        Instant from = Instant.now().minus(windowDuration);
        
        List<CalculatorRun> runs = runRepository.findByCalculatorIdAndTenantIdAndCreatedAtAfter(
            calculatorId, tenantId, from);
        
        int totalRuns = runs.size();
        int failedRuns = (int) runs.stream()
            .filter(r -> r.getStatus() != RunStatus.SUCCESS)
            .count();
        
        Calculator calculator = calculatorRepository.findById(calculatorId)
            .orElseThrow(() -> new CalculatorNotFoundException(calculatorId));
        
        BigDecimal targetRate = calculator.getSlaTargetSuccessRate() != null 
            ? calculator.getSlaTargetSuccessRate() 
            : BigDecimal.valueOf(99.0);
        
        BigDecimal errorBudget = BigDecimal.valueOf(100).subtract(targetRate);
        BigDecimal currentErrorRate = totalRuns > 0 
            ? BigDecimal.valueOf(failedRuns * 100.0 / totalRuns)
            : BigDecimal.ZERO;
        
        BigDecimal burnRate = errorBudget.compareTo(BigDecimal.ZERO) > 0
            ? currentErrorRate.divide(errorBudget, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        String severity;
        if (burnRate.compareTo(BigDecimal.valueOf(2.0)) > 0) {
            severity = "CRITICAL";
        } else if (burnRate.compareTo(BigDecimal.valueOf(1.0)) > 0) {
            severity = "WARNING";
        } else {
            severity = "OK";
        }
        
        return BurnRateResponse.builder()
            .calculatorId(calculatorId)
            .window(window)
            .currentBurnRate(burnRate)
            .allowedBurnRate(BigDecimal.ONE)
            .severity(severity)
            .build();
    }
    
    public Page<SlaBreachResponse> getBreachHistory(String calculatorId, String tenantId, 
                                                      Instant from, Instant to, Pageable pageable) {
        Page<SlaBreachEvent> breaches;
        
        if (calculatorId != null) {
            breaches = breachRepository.findByCalculatorIdAndTenantIdAndCreatedAtBetween(
                calculatorId, tenantId, from != null ? from : Instant.EPOCH, 
                to != null ? to : Instant.now(), pageable);
        } else {
            breaches = breachRepository.findByTenantIdAndCreatedAtBetween(
                tenantId, from != null ? from : Instant.EPOCH, 
                to != null ? to : Instant.now(), pageable);
        }
        
        return breaches.map(this::toBreachResponse);
    }
    
    private SlaBreachResponse toBreachResponse(SlaBreachEvent breach) {
        return SlaBreachResponse.builder()
            .breachId(breach.getBreachId())
            .runId(breach.getRunId())
            .calculatorId(breach.getCalculatorId())
            .breachType(breach.getBreachType())
            .expectedValue(breach.getExpectedValue())
            .actualValue(breach.getActualValue())
            .severity(breach.getSeverity())
            .alerted(breach.getAlerted())
            .createdAt(breach.getCreatedAt())
            .build();
    }
}
```

---

## 7. Scheduled SLI Aggregation Job

```java
@Component
@Slf4j
public class SliAggregationJob {
    
    private final CalculatorRunRepository runRepository;
    private final SliHistoryRepository sliHistoryRepository;
    private final List<String> tenantIds; // Inject from config or DB
    
    // Run every hour
    @Scheduled(cron = "0 0 * * * *")
    public void aggregateHourlySli() {
        log.info("Starting hourly SLI aggregation");
        
        Instant now = Instant.now();
        Instant hourStart = now.truncatedTo(ChronoUnit.HOURS);
        Instant hourEnd = hourStart.plus(1, ChronoUnit.HOURS);
        
        for (String tenantId : tenantIds) {
            aggregateSliForTenant(tenantId, hourStart, hourEnd, "1h");
        }
    }
    
    // Run daily at midnight
    @Scheduled(cron = "0 0 0 * * *")
    public void aggregateDailySli() {
        log.info("Starting daily SLI aggregation");
        
        Instant now = Instant.now();
        Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
        Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
        
        for (String tenantId : tenantIds) {
            aggregateSliForTenant(tenantId, dayStart, dayEnd, "1d");
        }
    }
    
    @Transactional
    protected void aggregateSliForTenant(String tenantId, Instant start, Instant end, String bucketDuration) {
        // Get all calculators with runs in this period
        List<String> calculatorIds = runRepository.findDistinctCalculatorIdsByTenantIdAndCreatedAtBetween(
            tenantId, start, end);
        
        for (String calculatorId : calculatorIds) {
            try {
                aggregateSliForCalculator(calculatorId, tenantId, start, end, bucketDuration);
            } catch (Exception e) {
                log.error("Failed to aggregate SLI for calculator {} in tenant {}", 
                    calculatorId, tenantId, e);
            }
        }
    }
    
    private void aggregateSliForCalculator(String calculatorId, String tenantId, 
                                            Instant start, Instant end, String bucketDuration) {
        List<CalculatorRun> runs = runRepository.findByCalculatorIdAndTenantIdAndCreatedAtBetween(
            calculatorId, tenantId, start, end);
        
        if (runs.isEmpty()) {
            return;
        }
        
        int totalRuns = runs.size();
        int successfulRuns = (int) runs.stream()
            .filter(r -> r.getStatus() == RunStatus.SUCCESS)
            .count();
        int failedRuns = (int) runs.stream()
            .filter(r -> r.getStatus() == RunStatus.FAILED)
            .count();
        int slaBreaches = (int) runs.stream()
            .filter(r -> Boolean.TRUE.equals(r.getSlaBreached()))
            .count();
        
        List<Long> durations = runs.stream()
            .map(CalculatorRun::getDurationMs)
            .filter(Objects::nonNull)
            .sorted()
            .collect(Collectors.toList());
        
        Long avgDuration = durations.isEmpty() ? null 
            : (long) durations.stream().mapToLong(Long::longValue).average().orElse(0);
        Long p95 = calculatePercentile(durations, 95);
        Long p99 = calculatePercentile(durations, 99);
        
        // Clock time analysis
        ZoneId cetZone = ZoneId.of("CET");
        double avgStartHour = runs.stream()
            .map(r -> ZonedDateTime.ofInstant(r.getStartTime(), cetZone).getHour() + 
                      ZonedDateTime.ofInstant(r.getStartTime(), cetZone).getMinute() / 60.0)
            .collect(Collectors.averagingDouble(Double::doubleValue));
        
        double avgEndHour = runs.stream()
            .filter(r -> r.getEndTime() != null)
            .map(r -> ZonedDateTime.ofInstant(r.getEndTime(), cetZone).getHour() + 
                      ZonedDateTime.ofInstant(r.getEndTime(), cetZone).getMinute() / 60.0)
            .collect(Collectors.averagingDouble(Double::doubleValue));
        
        SliHistory sli = new SliHistory();
        sli.setCalculatorId(calculatorId);
        sli.setTenantId(tenantId);
        sli.setTimeBucket(start);
        sli.setBucketDuration(bucketDuration);
        sli.setTotalRuns(totalRuns);
        sli.setSuccessfulRuns(successfulRuns);
        sli.setFailedRuns(failedRuns);
        sli.setAvgDurationMs(avgDuration);
        sli.setP95DurationMs(p95);
        sli.setP99DurationMs(p99);
        sli.setSlaBreaches(slaBreaches);
        sli.setAvgStartHourCet(BigDecimal.valueOf(avgStartHour).setScale(2, RoundingMode.HALF_UP));
        sli.setAvgEndHourCet(BigDecimal.valueOf(avgEndHour).setScale(2, RoundingMode.HALF_UP));
        
        sliHistoryRepository.save(sli);
        
        log.info("Aggregated SLI for calculator {} in tenant {} for bucket {}", 
            calculatorId, tenantId, start);
    }
    
    private Long calculatePercentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return null;
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
```

---

## 8. OpenTelemetry Integration

```java
@Service
@Slf4j
public class OpenTelemetryService {
    
    private final Meter meter;
    private final Tracer tracer;
    
    public OpenTelemetryService(MeterProvider meterProvider) {
        this.meter = meterProvider.get("observability-service");
        this.tracer = GlobalOpenTelemetry.getTracer("observability-service");
        
        // Register custom metrics
        registerMetrics();
    }
    
    private void registerMetrics() {
        // SLA breach counter
        meter.counterBuilder("sla.breaches")
            .setDescription("Count of SLA breaches")
            .setUnit("1")
            .build();
        
        // Run duration histogram
        meter.histogramBuilder("calculator.run.duration")
            .setDescription("Duration of calculator runs")
            .setUnit("ms")
            .build();
        
        // Success rate gauge
        meter.gaugeBuilder("calculator.success.rate")
            .setDescription("Success rate of calculator runs")
            .setUnit("percent")
            .buildWithCallback(measurement -> {
                // This would be called periodically to report current values
            });
    }
    
    public void sen


dBreachAlert(SlaBreachEvent breach) {
        Span span = tracer.spanBuilder("sla.breach.alert")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Add attributes
            span.setAttribute("calculator.id", breach.getCalculatorId());
            span.setAttribute("tenant.id", breach.getTenantId());
            span.setAttribute("breach.type", breach.getBreachType());
            span.setAttribute("severity", breach.getSeverity().name());
            
            // Record metric
            meter.counterBuilder("sla.breaches")
                .build()
                .add(1, 
                    Attributes.of(
                        AttributeKey.stringKey("calculator_id"), breach.getCalculatorId(),
                        AttributeKey.stringKey("breach_type"), breach.getBreachType(),
                        AttributeKey.stringKey("severity"), breach.getSeverity().name()
                    ));
            
            // Add event to span
            span.addEvent("SLA breach detected",
                Attributes.of(
                    AttributeKey.longKey("expected_value"), breach.getExpectedValue(),
                    AttributeKey.longKey("actual_value"), breach.getActualValue()
                ));
            
            log.warn("SLA breach alert sent to Azure Monitor: {}", breach.getBreachId());
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Failed to send breach alert");
            log.error("Failed to send SLA breach alert", e);
        } finally {
            span.end();
        }
    }
    
    public void recordRunMetrics(CalculatorRun run) {
        if (run.getDurationMs() != null) {
            meter.histogramBuilder("calculator.run.duration")
                .build()
                .record(run.getDurationMs(),
                    Attributes.of(
                        AttributeKey.stringKey("calculator_id"), run.getCalculatorId(),
                        AttributeKey.stringKey("status"), run.getStatus().name()
                    ));
        }
    }
}

// Configuration
@Configuration
public class OpenTelemetryConfig {
    
    @Bean
    public OpenTelemetry openTelemetry() {
        // Use Azure Monitor OpenTelemetry Distro
        return AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(() -> Map.of(
                "otel.service.name", "observability-service",
                "otel.traces.exporter", "azuremonitor",
                "otel.metrics.exporter", "azuremonitor",
                "otel.logs.exporter", "azuremonitor"
            ))
            .build()
            .getOpenTelemetrySdk();
    }
    
    @Bean
    public MeterProvider meterProvider(OpenTelemetry openTelemetry) {
        return openTelemetry.getMeterProvider();
    }
}
```

---

## 9. Multi-tenancy & Security

```java
// Tenant Context
@Component
@Scope(value = WebApplicationScope.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class TenantContext {
    
    private String tenantId;
    private String userId;
    private Set<String> roles;
    
    // Getters and setters
}

// Tenant Filter
@Component
@Order(1)
public class TenantContextFilter extends OncePerRequestFilter {
    
    private final TenantContext tenantContext;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                     HttpServletResponse response, 
                                     FilterChain filterChain) 
            throws ServletException, IOException {
        
        String tenantId = request.getHeader("X-Tenant-Id");
        
        if (StringUtils.hasText(tenantId)) {
            tenantContext.setTenantId(tenantId);
        }
        
        // Extract user info from Azure AD token
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            tenantContext.setUserId(auth.getName());
            tenantContext.setRoles(auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet()));
        }
        
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Clear context
            tenantContext.setTenantId(null);
            tenantContext.setUserId(null);
            tenantContext.setRoles(null);
        }
    }
}

// Security Configuration
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            )
            .csrf(csrf -> csrf.disable());
        
        return http.build();
    }
    
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new AzureAdJwtGrantedAuthoritiesConverter());
        return converter;
    }
}

// Custom JWT converter for Azure AD
public class AzureAdJwtGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<String> roles = jwt.getClaimAsStringList("roles");
        
        if (roles == null) {
            return Collections.emptyList();
        }
        
        return roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .collect(Collectors.toList());
    }
}
```

---

## 10. Caching Strategy

```java
@Configuration
@EnableCaching
public class CacheConfig {
    
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
            );
        
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // Different TTLs for different caches
        cacheConfigurations.put("runs", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("recentRuns", defaultConfig.entryTtl(Duration.ofMinutes(2)));
        cacheConfigurations.put("sloSummary", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put("calculators", defaultConfig.entryTtl(Duration.ofHours(1)));
        
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .build();
    }
}

// Cache eviction on updates
@Service
public class CacheEvictionService {
    
    private final CacheManager cacheManager;
    
    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        evictRunCaches(event.getRun());
    }
    
    @EventListener
    public void onRunFailed(RunFailedEvent event) {
        evictRunCaches(event.getRun());
    }
    
    private void evictRunCaches(CalculatorRun run) {
        // Evict specific run
        Cache runsCache = cacheManager.getCache("runs");
        if (runsCache != null) {
            runsCache.evict(run.getRunId());
        }
        
        // Evict recent runs for this calculator
        Cache recentRunsCache = cacheManager.getCache("recentRuns");
        if (recentRunsCache != null) {
            recentRunsCache.evict(run.getTenantId() + "-" + run.getCalculatorId() + "-10");
        }
        
        // Evict SLO summary
        Cache sloCache = cacheManager.getCache("sloSummary");
        if (sloCache != null) {
            sloCache.evict(run.getCalculatorId() + "-30d-" + run.getTenantId());
        }
    }
}
```

---

## 11. Application Configuration

```yaml
# application.yml
spring:
  application:
    name: observability-service
  
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST}:5432/${POSTGRES_DB}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
  
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc:
          batch_size: 20
        order_inserts: true
        order_updates: true
    show-sql: false
  
  data:
    redis:
      host: ${REDIS_HOST}
      port: 6379
      password: ${REDIS_PASSWORD}
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2
  
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://login.microsoftonline.com/${AZURE_TENANT_ID}/v2.0
          jwk-set-uri: https://login.microsoftonline.com/${AZURE_TENANT_ID}/discovery/v2.0/keys

# OpenTelemetry
otel:
  service:
    name: observability-service
  traces:
    exporter: azuremonitor
  metrics:
    exporter: azuremonitor
  logs:
    exporter: azuremonitor
  instrumentation:
    spring-web:
      enabled: true
    jdbc:
      enabled: true

# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
  endpoint:
    health:
      show-details: when-authorized

# Logging
logging:
  level:
    root: INFO
    com.yourcompany.observability: DEBUG
    org.hibernate.SQL: DEBUG
    org.springframework.security: DEBUG

# Custom properties
observability:
  sli:
    aggregation:
      enabled: true
      hourly-cron: "0 0 * * * *"
      daily-cron: "0 0 0 * * *"
  sla:
    default-target-success-rate: 99.0
    default-target-duration-ms: 300000
```

---

## 12. Deployment Configuration (Kubernetes)

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: observability-service
  namespace: observability
spec:
  replicas: 3
  selector:
    matchLabels:
      app: observability-service
  template:
    metadata:
      labels:
        app: observability-service
    spec:
      containers:
      - name: observability-service
        image: ${ACR_NAME}.azurecr.io/observability-service:${VERSION}
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "production"
        - name: POSTGRES_HOST
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: host
        - name: POSTGRES_DB
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: database
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: username
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
        - name: REDIS_HOST
          valueFrom:
            secretKeyRef:
              name: redis-credentials
              key: host
        - name: REDIS_PASSWORD
          valueFrom:
            secretKeyRef:
              name: redis-credentials
              key: password
        - name: AZURE_TENANT_ID
          valueFrom:
            secretKeyRef:
              name: azure-credentials
              key: tenant-id
        - name: APPLICATIONINSIGHTS_CONNECTION_STRING
          valueFrom:
            secretKeyRef:
              name: azure-monitor-credentials
              key: connection-string
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: observability-service
  namespace: observability
spec:
  selector:
    app: observability-service
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: ClusterIP
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: observability-service-ingress
  namespace: observability
  annotations:
    kubernetes.io/ingress.class: nginx
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  tls:
  - hosts:
    - observability-api.yourdomain.com
    secretName: observability-tls
  rules:
  - host: observability-api.yourdomain.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: observability-service
            port:
              number: 80
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: observability-service-hpa
  namespace: observability
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: observability-service
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

---

## 13. pom.xml Dependencies

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
    </parent>
    
    <groupId>com.yourcompany</groupId>
    <artifactId>observability-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>Observability Service</name>
    
    <properties>
        <java.version>17</java.version>
        <opentelemetry.version>1.32.0</opentelemetry.version>
    </properties>
    
    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        
        <!-- Database -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        
        <dependency>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </dependency>
        
        <dependency>
            <groupId>io.hypersistence</groupId>
            <artifactId>hypersistence-utils-hibernate-62</artifactId>
            <version>3.6.1</version>
        </dependency>
        
        <!-- Redis -->
        <dependency>
            <groupId>io.lettuce</groupId>
            <artifactId>lettuce-core</artifactId>
        </dependency>
        
        <!-- OpenAPI/Swagger -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.2.0</version>
        </dependency>
        
        <!-- OpenTelemetry -->
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-api</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        
        <dependency>
            <groupId>io.opentelemetry.instrumentation</groupId>
            <artifactId>opentelemetry-spring-boot-starter</artifactId>
            <version>1.32.0-alpha</version>
        </dependency>
        
        <!-- Azure Monitor OpenTelemetry -->
        <dependency>
            <groupId>com.azure</groupId>
            <artifactId>azure-monitor-opentelemetry-exporter</artifactId>
            <version>1.0.0-beta.13</version>
        </dependency>
        
        <!-- Micrometer for Prometheus -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        
        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        
        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>1.19.3</version>
            <scope>test</scope>
        </dependency>
        
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>1.19.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 14. Key Design Decisions & Rationale

### **Write Path Optimization**
- **Async Event Processing**: Use Spring Events for non-blocking ingestion
- **Batch Writes**: Hibernate batch inserts for bulk operations
- **Partition Strategy**: Monthly partitions on `calculator_runs` for query performance

### **Read Path Optimization**
- **Redis Caching**: Aggressive caching of frequently accessed data (runs, SLO summaries)
- **Pre-computed SLI**: Scheduled jobs aggregate metrics into `sli_history` table
- **Database Indexes**: Strategic indexes on high-cardinality columns

### **SLO/SLA Management**
- **Hybrid Approach**: Pre-computed daily/hourly aggregates + on-demand calculations
- **Error Budget Tracking**: Real-time burn rate calculations
- **Zoned Clock Time**: CET-based time analysis for business hour insights

### **Multi-tenancy**
- **Row-level Security**: Tenant ID embedded in all queries
- **Context Propagation**: Request-scoped `TenantContext` for isolation
- **RBAC**: Azure AD integration with role-based access control

### **Observability**
- **OpenTelemetry**: Native instrumentation for traces, metrics, logs
- **Azure Monitor**: Direct integration via OpenTelemetry exporters
- **Custom Metrics**: SLA breaches, run durations, success rates

### **Scalability**
- **Horizontal Scaling**: Stateless service, scales via HPA
- **Database Partitioning**: Time-based partitions for large tables
- **Connection Pooling**: HikariCP with optimized pool sizes
- **Cache Distribution**: Redis for shared cache across replicas

---

## 15. Testing Strategy

```java
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class RunIngestionControllerIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    
    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private CalculatorRunRepository runRepository;
    
    @Test
    @WithMockUser(username = "test-user", roles = {"USER"})
    void testCreateRun() throws Exception {
        CreateRunRequest request = CreateRunRequest.builder()
            .runId("test-run-123")
            .calculatorId("calc-1")
            .startTime(Instant.now())
            .build();
        
        mockMvc.perform(post("/api/v1/runs")
                .header("X-Tenant-Id", "tenant-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.runId").value("test-run-123"))
            .andExpect(jsonPath("$.status").value("RUNNING"));
        
        Optional<CalculatorRun> savedRun = runRepository.findById("test-run-123");
        assertThat(savedRun).isPresent();
        assertThat(savedRun.get().getStatus()).isEqualTo(RunStatus.RUNNING);
    }
}
```

---

## Summary

This design provides:

1. **High-throughput ingestion** via async processing and batch writes
2. **Low-latency queries** via Redis caching and pre-computed aggregates
3. **Comprehensive SLO/SLA management** with error budget tracking and burn rate calculations
4. **Multi-tenancy & RBAC** integrated with Azure AD
5. **OpenTelemetry integration** for native observability and Azure Monitor alerting
6. **Production-ready** with proper error handling, testing, and Kubernetes deployment

The architecture is designed to handle high-cardinality writes while maintaining excellent read performance through strategic caching and pre-aggregation. The system scales horizontally and integrates seamlessly with your existing Azure infrastructure.