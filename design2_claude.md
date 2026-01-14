# Observability Backend Service - Revised Design

I'll redesign the service with your specific requirements, focusing on Spring Data JPA with native SQL, clock time analysis in CET, and Airflow integration.

## 1. Revised Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Airflow (Orchestrator)                    │
│  - Triggers calculator runs                                  │
│  - Sends start events → POST /api/v1/runs/start             │
│  - Sends end events → POST /api/v1/runs/complete            │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│              Observability Service (AKS)                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │    Ingestion API (from Airflow)                      │  │
│  │  - /runs/start (create run + metadata)               │  │
│  │  - /runs/complete (end time + duration calc)         │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │    Live SLA Breach Detection                         │  │
│  │  - Real-time evaluation on run completion            │  │
│  │  - Immediate alert to Azure Monitor                  │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │    Query API (for UI/Consumers)                      │  │
│  │  - Last N runs                                       │  │
│  │  - Average runtime by period                         │  │
│  │  - Clock time analysis (CET)                         │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │    Scheduled Aggregation Jobs                        │  │
│  │  - Daily: compute avg runtime, clock times           │  │
│  │  - Cache results for fast queries                    │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
           ↓                    ↓                    ↓
    ┌──────────┐         ┌──────────┐        ┌─────────────┐
    │PostgreSQL│         │  Redis   │        │Azure Monitor│
    │   (RDS)  │         │  Cache   │        │  (Alerts)   │
    └──────────┘         └──────────┘        └─────────────┘
```

---

## 2. Simplified Database Schema

```sql
-- Core table for calculator run tracking
CREATE TABLE calculator_runs (
    run_id VARCHAR(100) PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(200) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    
    -- Timing (all UTC internally, converted to CET for analysis)
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    duration_ms BIGINT,
    
    -- Clock times in CET (hours with decimals, e.g., 14.5 = 2:30 PM)
    start_hour_cet DECIMAL(5,2),
    end_hour_cet DECIMAL(5,2),
    
    -- Status
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    
    -- SLA configuration (copied from metadata at run start)
    sla_duration_ms BIGINT,
    sla_end_hour_cet DECIMAL(5,2), -- Expected completion hour in CET
    
    -- SLA breach tracking
    sla_breached BOOLEAN DEFAULT FALSE,
    sla_breach_reason VARCHAR(100),
    
    -- Run parameters (from Airflow)
    run_parameters JSONB,
    
    -- Audit
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT chk_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT'))
);

-- Indexes for efficient querying
CREATE INDEX idx_runs_calculator_created ON calculator_runs(calculator_id, created_at DESC);
CREATE INDEX idx_runs_tenant_created ON calculator_runs(tenant_id, created_at DESC);
CREATE INDEX idx_runs_status ON calculator_runs(status);
CREATE INDEX idx_runs_sla_breach ON calculator_runs(calculator_id, sla_breached) 
    WHERE sla_breached = TRUE;

-- Calculator metadata and SLA configuration
CREATE TABLE calculators (
    calculator_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    
    -- SLA targets
    sla_target_duration_ms BIGINT NOT NULL,
    sla_target_end_hour_cet DECIMAL(5,2), -- e.g., 16.0 = 4:00 PM CET
    
    -- Ownership
    owner_team VARCHAR(100),
    
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Pre-aggregated statistics for fast queries
CREATE TABLE calculator_statistics (
    stat_id BIGSERIAL PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50),
    
    -- Time window
    period_days INTEGER NOT NULL, -- 30, 60, 90
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    
    -- Aggregated metrics
    total_runs INTEGER NOT NULL,
    successful_runs INTEGER NOT NULL,
    failed_runs INTEGER NOT NULL,
    
    -- Duration statistics
    avg_duration_ms BIGINT,
    min_duration_ms BIGINT,
    max_duration_ms BIGINT,
    
    -- Clock time statistics (in CET)
    avg_start_hour_cet DECIMAL(5,2),
    avg_end_hour_cet DECIMAL(5,2),
    
    -- SLA tracking
    sla_breaches INTEGER DEFAULT 0,
    
    computed_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(calculator_id, tenant_id, period_days, period_start)
);

CREATE INDEX idx_stats_calculator_period ON calculator_statistics(calculator_id, period_days);

-- SLA breach events for alerting
CREATE TABLE sla_breach_events (
    breach_id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) NOT NULL REFERENCES calculator_runs(run_id),
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(200) NOT NULL,
    tenant_id VARCHAR(50),
    
    breach_type VARCHAR(50) NOT NULL, -- DURATION_EXCEEDED, TIME_EXCEEDED, FAILED
    
    expected_value BIGINT,
    actual_value BIGINT,
    
    severity VARCHAR(20) DEFAULT 'MEDIUM', -- LOW, MEDIUM, HIGH, CRITICAL
    
    -- Alerting status
    alerted BOOLEAN DEFAULT FALSE,
    alerted_at TIMESTAMPTZ,
    alert_status VARCHAR(20), -- PENDING, SENT, FAILED
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_breach_calculator ON sla_breach_events(calculator_id, created_at DESC);
CREATE INDEX idx_breach_alerted ON sla_breach_events(alerted) WHERE alerted = FALSE;
```

---

## 3. Domain Models (Plain POJOs)

```java
// Entity: CalculatorRun.java
@Table(name = "calculator_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorRun {
    
    @Id
    @Column(name = "run_id", length = 100)
    private String runId;
    
    @Column(name = "calculator_id", nullable = false, length = 100)
    private String calculatorId;
    
    @Column(name = "calculator_name", nullable = false, length = 200)
    private String calculatorName;
    
    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;
    
    @Column(name = "start_time", nullable = false)
    private Instant startTime;
    
    @Column(name = "end_time")
    private Instant endTime;
    
    @Column(name = "duration_ms")
    private Long durationMs;
    
    @Column(name = "start_hour_cet", precision = 5, scale = 2)
    private BigDecimal startHourCet;
    
    @Column(name = "end_hour_cet", precision = 5, scale = 2)
    private BigDecimal endHourCet;
    
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    
    @Column(name = "sla_duration_ms")
    private Long slaDurationMs;
    
    @Column(name = "sla_end_hour_cet", precision = 5, scale = 2)
    private BigDecimal slaEndHourCet;
    
    @Column(name = "sla_breached")
    private Boolean slaBreached;
    
    @Column(name = "sla_breach_reason", length = 100)
    private String slaBreachReason;
    
    @Column(name = "run_parameters", columnDefinition = "jsonb")
    private String runParameters; // JSON string
    
    @Column(name = "created_at")
    private Instant createdAt;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
}

// Entity: Calculator.java
@Table(name = "calculators")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Calculator {
    
    @Id
    @Column(name = "calculator_id", length = 100)
    private String calculatorId;
    
    @Column(nullable = false, length = 200)
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "sla_target_duration_ms", nullable = false)
    private Long slaTargetDurationMs;
    
    @Column(name = "sla_target_end_hour_cet", precision = 5, scale = 2)
    private BigDecimal slaTargetEndHourCet;
    
    @Column(name = "owner_team", length = 100)
    private String ownerTeam;
    
    @Column(nullable = false)
    private Boolean active;
    
    @Column(name = "created_at")
    private Instant createdAt;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
}

// Entity: CalculatorStatistics.java
@Table(name = "calculator_statistics")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorStatistics {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stat_id")
    private Long statId;
    
    @Column(name = "calculator_id", nullable = false)
    private String calculatorId;
    
    @Column(name = "tenant_id")
    private String tenantId;
    
    @Column(name = "period_days", nullable = false)
    private Integer periodDays;
    
    @Column(name = "period_start", nullable = false)
    private Instant periodStart;
    
    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;
    
    @Column(name = "total_runs", nullable = false)
    private Integer totalRuns;
    
    @Column(name = "successful_runs", nullable = false)
    private Integer successfulRuns;
    
    @Column(name = "failed_runs", nullable = false)
    private Integer failedRuns;
    
    @Column(name = "avg_duration_ms")
    private Long avgDurationMs;
    
    @Column(name = "min_duration_ms")
    private Long minDurationMs;
    
    @Column(name = "max_duration_ms")
    private Long maxDurationMs;
    
    @Column(name = "avg_start_hour_cet", precision = 5, scale = 2)
    private BigDecimal avgStartHourCet;
    
    @Column(name = "avg_end_hour_cet", precision = 5, scale = 2)
    private BigDecimal avgEndHourCet;
    
    @Column(name = "sla_breaches")
    private Integer slaBreaches;
    
    @Column(name = "computed_at")
    private Instant computedAt;
}

// Entity: SlaBreachEvent.java
@Table(name = "sla_breach_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaBreachEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "breach_id")
    private Long breachId;
    
    @Column(name = "run_id", nullable = false)
    private String runId;
    
    @Column(name = "calculator_id", nullable = false)
    private String calculatorId;
    
    @Column(name = "calculator_name", nullable = false)
    private String calculatorName;
    
    @Column(name = "tenant_id")
    private String tenantId;
    
    @Column(name = "breach_type", nullable = false)
    private String breachType;
    
    @Column(name = "expected_value")
    private Long expectedValue;
    
    @Column(name = "actual_value")
    private Long actualValue;
    
    @Column(length = 20)
    private String severity;
    
    @Column(nullable = false)
    private Boolean alerted;
    
    @Column(name = "alerted_at")
    private Instant alertedAt;
    
    @Column(name = "alert_status", length = 20)
    private String alertStatus;
    
    @Column(name = "created_at")
    private Instant createdAt;
}
```

---

## 4. Repository Layer with Native SQL

```java
// CalculatorRunRepository.java
@Repository
public interface CalculatorRunRepository extends JpaRepository<CalculatorRun, String> {
    
    // Find last N runs for a calculator
    @Query(value = """
        SELECT * FROM calculator_runs 
        WHERE calculator_id = :calculatorId 
        AND tenant_id = :tenantId 
        ORDER BY created_at DESC 
        LIMIT :limit
        """, nativeQuery = true)
    List<CalculatorRun> findLastNRuns(
        @Param("calculatorId") String calculatorId,
        @Param("tenantId") String tenantId,
        @Param("limit") int limit
    );
    
    // Find runs within a date range
    @Query(value = """
        SELECT * FROM calculator_runs 
        WHERE calculator_id = :calculatorId 
        AND tenant_id = :tenantId 
        AND created_at >= :startDate 
        AND created_at < :endDate 
        ORDER BY created_at DESC
        """, nativeQuery = true)
    List<CalculatorRun> findRunsInPeriod(
        @Param("calculatorId") String calculatorId,
        @Param("tenantId") String tenantId,
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );
    
    // Check if run exists
    @Query(value = """
        SELECT EXISTS(
            SELECT 1 FROM calculator_runs 
            WHERE run_id = :runId 
            AND tenant_id = :tenantId
        )
        """, nativeQuery = true)
    boolean existsByRunIdAndTenantId(
        @Param("runId") String runId,
        @Param("tenantId") String tenantId
    );
}

// Custom repository for complex native queries
@Repository
public class CalculatorRunCustomRepository {
    
    @PersistenceContext
    private EntityManager entityManager;
    
    /**
     * Calculate average runtime statistics for a calculator over a period
     */
    public Map<String, Object> calculateAverageRuntime(
            String calculatorId, 
            String tenantId, 
            int periodDays) {
        
        String sql = """
            SELECT 
                COUNT(*) as total_runs,
                AVG(duration_ms) as avg_duration_ms,
                MIN(duration_ms) as min_duration_ms,
                MAX(duration_ms) as max_duration_ms,
                AVG(start_hour_cet) as avg_start_hour_cet,
                AVG(end_hour_cet) as avg_end_hour_cet,
                COUNT(*) FILTER (WHERE sla_breached = true) as sla_breaches
            FROM calculator_runs
            WHERE calculator_id = :calculatorId
            AND tenant_id = :tenantId
            AND status IN ('SUCCESS', 'FAILED')
            AND created_at >= NOW() - INTERVAL ':periodDays days'
            """;
        
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("calculatorId", calculatorId);
        query.setParameter("tenantId", tenantId);
        query.setParameter("periodDays", periodDays);
        
        Object[] result = (Object[]) query.getSingleResult();
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRuns", ((Number) result[0]).intValue());
        stats.put("avgDurationMs", result[1] != null ? ((Number) result[1]).longValue() : null);
        stats.put("minDurationMs", result[2] != null ? ((Number) result[2]).longValue() : null);
        stats.put("maxDurationMs", result[3] != null ? ((Number) result[3]).longValue() : null);
        stats.put("avgStartHourCet", result[4] != null ? new BigDecimal(result[4].toString()) : null);
        stats.put("avgEndHourCet", result[5] != null ? new BigDecimal(result[5].toString()) : null);
        stats.put("slaBreaches", ((Number) result[6]).intValue());
        
        return stats;
    }
    
    /**
     * Insert or update calculator run (upsert)
     */
    @Transactional
    public void upsertCalculatorRun(CalculatorRun run) {
        String sql = """
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id,
                start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                status, sla_duration_ms, sla_end_hour_cet, sla_breached, sla_breach_reason,
                run_parameters, created_at, updated_at
            ) VALUES (
                :runId, :calculatorId, :calculatorName, :tenantId,
                :startTime, :endTime, :durationMs, :startHourCet, :endHourCet,
                :status, :slaDurationMs, :slaEndHourCet, :slaBreached, :slaBreachReason,
                :runParameters::jsonb, :createdAt, :updatedAt
            )
            ON CONFLICT (run_id) DO UPDATE SET
                end_time = EXCLUDED.end_time,
                duration_ms = EXCLUDED.duration_ms,
                end_hour_cet = EXCLUDED.end_hour_cet,
                status = EXCLUDED.status,
                sla_breached = EXCLUDED.sla_breached,
                sla_breach_reason = EXCLUDED.sla_breach_reason,
                updated_at = EXCLUDED.updated_at
            """;
        
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("runId", run.getRunId());
        query.setParameter("calculatorId", run.getCalculatorId());
        query.setParameter("calculatorName", run.getCalculatorName());
        query.setParameter("tenantId", run.getTenantId());
        query.setParameter("startTime", run.getStartTime());
        query.setParameter("endTime", run.getEndTime());
        query.setParameter("durationMs", run.getDurationMs());
        query.setParameter("startHourCet", run.getStartHourCet());
        query.setParameter("endHourCet", run.getEndHourCet());
        query.setParameter("status", run.getStatus());
        query.setParameter("slaDurationMs", run.getSlaDurationMs());
        query.setParameter("slaEndHourCet", run.getSlaEndHourCet());
        query.setParameter("slaBreached", run.getSlaBreached());
        query.setParameter("slaBreachReason", run.getSlaBreachReason());
        query.setParameter("runParameters", run.getRunParameters());
        query.setParameter("createdAt", run.getCreatedAt());
        query.setParameter("updatedAt", run.getUpdatedAt());
        
        query.executeUpdate();
    }
}

// CalculatorRepository.java
@Repository
public interface CalculatorRepository extends JpaRepository<Calculator, String> {
    
    @Query(value = """
        SELECT * FROM calculators 
        WHERE active = true
        """, nativeQuery = true)
    List<Calculator> findAllActive();
}

// CalculatorStatisticsRepository.java
@Repository
public interface CalculatorStatisticsRepository extends JpaRepository<CalculatorStatistics, Long> {
    
    @Query(value = """
        SELECT * FROM calculator_statistics 
        WHERE calculator_id = :calculatorId 
        AND tenant_id = :tenantId 
        AND period_days = :periodDays 
        ORDER BY computed_at DESC 
        LIMIT 1
        """, nativeQuery = true)
    Optional<CalculatorStatistics> findLatestStatistics(
        @Param("calculatorId") String calculatorId,
        @Param("tenantId") String tenantId,
        @Param("periodDays") int periodDays
    );
}

// SlaBreachEventRepository.java
@Repository
public interface SlaBreachEventRepository extends JpaRepository<SlaBreachEvent, Long> {
    
    @Query(value = """
        SELECT * FROM sla_breach_events 
        WHERE alerted = false 
        ORDER BY created_at ASC 
        LIMIT :limit
        """, nativeQuery = true)
    List<SlaBreachEvent> findUnalertedBreaches(@Param("limit") int limit);
}
```

---

## 5. Service Layer - Run Ingestion from Airflow

```java
@Service
@Slf4j
public class RunIngestionService {
    
    private final CalculatorRunCustomRepository customRepository;
    private final CalculatorRepository calculatorRepository;
    private final SlaEvaluationService slaEvaluationService;
    private final ApplicationEventPublisher eventPublisher;
    
    /**
     * Called by Airflow when calculator starts
     */
    @Transactional
    public CalculatorRun startRun(StartRunRequest request, String tenantId) {
        log.info("Starting run {} for calculator {} in tenant {}", 
            request.getRunId(), request.getCalculatorId(), tenantId);
        
        // Get calculator metadata for SLA
        Calculator calculator = calculatorRepository.findById(request.getCalculatorId())
            .orElseThrow(() -> new CalculatorNotFoundException(request.getCalculatorId()));
        
        // Calculate CET hour from start time
        BigDecimal startHourCet = calculateCetHour(request.getStartTime());
        
        CalculatorRun run = CalculatorRun.builder()
            .runId(request.getRunId())
            .calculatorId(request.getCalculatorId())
            .calculatorName(calculator.getName())
            .tenantId(tenantId)
            .startTime(request.getStartTime())
            .startHourCet(startHourCet)
            .status("RUNNING")
            .slaDurationMs(calculator.getSlaTargetDurationMs())
            .slaEndHourCet(calculator.getSlaTargetEndHourCet())
            .runParameters(request.getRunParameters())
            .slaBreached(false)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        
        customRepository.upsertCalculatorRun(run);
        
        log.info("Run {} started successfully at CET hour {}", 
            run.getRunId(), startHourCet);
        
        return run;
    }
    
    /**
     * Called by Airflow when calculator completes
     */
    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        log.info("Completing run {} in tenant {}", runId, tenantId);
        
        // Fetch existing run
        CalculatorRun run = customRepository.entityManager
            .find(CalculatorRun.class, runId);
        
        if (run == null) {
            throw new RunNotFoundException(runId);
        }
        
        if (!run.getTenantId().equals(tenantId)) {
            throw new TenantAccessDeniedException(tenantId, runId);
        }
        
        // Calculate duration and CET end hour
        long durationMs = Duration.between(run.getStartTime(), request.getEndTime()).toMillis();
        BigDecimal endHourCet = calculateCetHour(request.getEndTime());
        
        // Update run
        run.setEndTime(request.getEndTime());
        run.setDurationMs(durationMs);
        run.setEndHourCet(endHourCet);
        run.setStatus(request.getStatus() != null ? request.getStatus() : "SUCCESS");
        run.setUpdatedAt(Instant.now());
        
        // Evaluate SLA breach
        SlaEvaluationResult slaResult = slaEvaluationService.evaluateSla(run);
        run.setSlaBreached(slaResult.isBreached());
        run.setSlaBreachReason(slaResult.getReason());
        
        customRepository.upsertCalculatorRun(run);
        
        log.info("Run {} completed with duration {}ms, CET end hour {}, SLA breached: {}", 
            runId, durationMs, endHourCet, slaResult.isBreached());
        
        // Publish event for async processing (alerting)
        if (slaResult.isBreached()) {
            eventPublisher.publishEvent(new SlaBreachedEvent(run, slaResult));
        }
        
        return run;
    }
    
    /**
     * Convert UTC instant to CET hour (decimal, e.g., 14.5 = 2:30 PM)
     */
    private BigDecimal calculateCetHour(Instant instant) {
        ZoneId cetZone = ZoneId.of("CET");
        ZonedDateTime cetTime = instant.atZone(cetZone);
        
        double hour = cetTime.getHour() + (cetTime.getMinute() / 60.0);
        return BigDecimal.valueOf(hour).setScale(2, RoundingMode.HALF_UP);
    }
}
```

---

## 6. SLA Evaluation Service

```java
@Service
@Slf4j
public class SlaEvaluationService {
    
    /**
     * Evaluate if a completed run breached SLA
     */
    public SlaEvaluationResult evaluateSla(CalculatorRun run) {
        List<String> breachReasons = new ArrayList<>();
        
        // Check 1: Duration exceeded
        if (run.getSlaDurationMs() != null && run.getDurationMs() != null) {
            if (run.getDurationMs() > run.getSlaDurationMs()) {
                breachReasons.add(String.format(
                    "Duration exceeded: %dms > %dms", 
                    run.getDurationMs(), 
                    run.getSlaDurationMs()
                ));
            }
        }
        
        // Check 2: End time exceeded target CET hour
        if (run.getSlaEndHourCet() != null && run.getEndHourCet() != null) {
            if (run.getEndHourCet().compareTo(run.getSlaEndHourCet()) > 0) {
                breachReasons.add(String.format(
                    "End time exceeded: CET %s > target %s", 
                    run.getEndHourCet(), 
                    run.getSlaEndHourCet()
                ));
            }
        }
        
        // Check 3: Run failed
        if ("FAILED".equals(run.getStatus()) || "TIMEOUT".equals(run.getStatus())) {
            breachReasons.add("Run status: " + run.getStatus());
        }
        
        boolean breached = !breachReasons.isEmpty();
        String reason = breached ? String.join("; ", breachReasons) : null;
        
        return new SlaEvaluationResult(breached, reason, determineSeverity(run, breachReasons));
    }
    
    private String determineSeverity(CalculatorRun run, List<String> breachReasons) {
        if (breachReasons.isEmpty()) {
            return null;
        }
        
        if ("FAILED".equals(run.getStatus())) {
            return "CRITICAL";
        }
        
        if (run.getSlaDurationMs() != null && run.getDurationMs() != null) {
            double overage = (double) run.getDurationMs() / run.getSlaDurationMs();
            if (overage > 2.0) return "CRITICAL";
            if (overage > 1.5) return "HIGH";
            if (overage > 1.2) return "MEDIUM";
        }
        
        return "LOW";
    }
}

@Data
@AllArgsConstructor
public class SlaEvaluationResult {
    private boolean breached;
    private String reason;
    private String severity;
}
```

---

## 7. Alert Handler for Azure Monitor

```java
@Service
@Slf4j
public class AlertHandlerService {
    
    private final SlaBreachEventRepository breachRepository;
    private final AzureMonitorAlertSender azureAlertSender;
    
    /**
     * Handle SLA breach event (triggered by Spring event)
     */
    @EventListener
    @Async
    @Transactional
    public void handleSlaBreachEvent(SlaBreachedEvent event) {
        CalculatorRun run = event.getRun();
        SlaEvaluationResult result = event.getResult();
        
        log.warn("Processing SLA breach for run {}: {}", run.getRunId(), result.getReason());
        
        // Create breach record
        SlaBreachEvent breach = SlaBreachEvent.builder()
            .runId(run.getRunId())
            .calculatorId(run.getCalculatorId())
            .calculatorName(run.getCalculatorName())
            .tenantId(run.getTenantId())
            .breachType(determineBreachType(result.getReason()))
            .expectedValue(run.getSlaDurationMs())
            .actualValue(run.getDurationMs())
            .severity(result.getSeverity())
            .alerted(false)
            .alertStatus("PENDING")
            .createdAt(Instant.now())
            .build();
        
        SlaBreachEvent savedBreach = breachRepository.save(breach);
        
        // Send alert to Azure Monitor
        try {
            azureAlertSender.sendAlert(savedBreach, run);
            
            savedBreach.setAlerted(true);
            savedBreach.setAlertedAt(Instant.now());
            savedBreach.setAlertStatus("SENT");
            breachRepository.save(savedBreach);
            
            log.info("Alert sent successfully for breach {}", savedBreach.getBreachId());
            
        } catch (Exception e) {
            log.error("Failed to send alert for breach {}", savedBreach.getBreachId(), e);
            
            savedBreach.setAlertStatus("FAILED");
            breachRepository.save(savedBreach);
        }
    }
    
    private String determineBreachType(String reason) {
        if (reason.contains("Duration exceeded")) return "DURATION_EXCEEDED";
        if (reason.contains("End time exceeded")) return "TIME_EXCEEDED";
        if (reason.contains("FAILED")) return "FAILED";
        if (reason.contains("TIMEOUT")) return "TIMEOUT";
        return "UNKNOWN";
    }
}

@Component
@Slf4j
public class AzureMonitorAlertSender {
    
    private final Tracer tracer;
    private final Meter meter;
    
    public void sendAlert(SlaBreachEvent breach, CalculatorRun run) {
        Span span = tracer.spanBuilder("sla.breach.alert")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Set attributes for Azure Monitor
            span.setAttribute("calculator.id", breach.getCalculatorId());
            span.setAttribute("calculator.name", breach.getCalculatorName());
            span.setAttribute("tenant.id", breach.getTenantId());
            span.setAttribute("run.id", breach.getRunId());
            span.setAttribute("breach.type", breach.getBreachType());
            span.setAttribute("severity", breach.getSeverity());
            
            // Add custom event
            span.addEvent("SLA Breach Detected",
                Attributes.of(
                    AttributeKey.stringKey("reason"), run.getSlaBreachReason(),
                    AttributeKey.longKey("expected_duration_ms"), breach.getExpectedValue() != null ? breach.getExpectedValue() : 0L,
                    AttributeKey.longKey("actual_duration_ms"), breach.getActualValue() != null ? breach.getActualValue() : 0L
                ));
            
            // Record custom metric for alerting
            meter.counterBuilder("sla.breach.alerts")
                .build()
                .add(1, 
                    Attributes.of(
                        AttributeKey.stringKey("calculator_id"), breach.getCalculatorId(),
                        AttributeKey.stringKey("breach_type"), breach.getBreachType(),
                        AttributeKey.stringKey("severity"), breach.getSeverity()
                    ));
            
            log.info("SLA breach alert sent to Azure Monitor for run {}", breach.getRunId());
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Failed to send alert");
            throw new AlertSendException("Failed to send alert to Azure Monitor", e);
        } finally {
            span.end();
        }
    }
}
```

---

## 8. Query API for Consumers

```java
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Query", description = "Query calculator run information")
public class RunQueryController {
    
    private final RunQueryService queryService;
    
    /**
     * Get last N runs for a calculator
     */
    @GetMapping("/calculator/{calculatorId}/recent")
    @Operation(summary = "Get last N runs for a calculator")
    @Cacheable(value = "recentRuns", key = "#calculatorId + '-' + #tenantId + '-' + #limit")
    public ResponseEntity<List<RunSummaryResponse>> getLastNRuns(
            @PathVariable String calculatorId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        List<RunSummaryResponse> runs = queryService.getLastNRuns(calculatorId, tenantId, limit);
        return ResponseEntity.ok(runs);
    }
    
    /**
     * Get average runtime statistics for a period
     */
    @GetMapping("/calculator/{calculatorId}/average-runtime")
    @Operation(summary = "Get average runtime statistics over a period")
    @Cacheable(value = "avgRuntime", key = "#calculatorId + '-' + #tenantId + '-' + #periodDays")
    public ResponseEntity<AverageRuntimeResponse> getAverageRuntime(
            @PathVariable String calculatorId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int periodDays,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        AverageRuntimeResponse response = queryService.getAverageRuntime(
            calculatorId, tenantId, periodDays);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get single run details
     */
    @GetMapping("/{runId}")
    @Operation(summary = "Get calculator run by ID")
    public ResponseEntity<RunDetailResponse> getRunById(
            @PathVariable String runId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        RunDetailResponse response = queryService.getRunById(runId, tenantId);
        return ResponseEntity.ok(response);
    }
}

// DTOs
@Data
@Builder
public class RunSummaryResponse {
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;
    private String status;
    private Boolean slaBreached;
    private String slaBreachReason;
}

@Data
@Builder
public class AverageRuntimeResponse {
    private String calculatorId;
    private Integer periodDays;
    private Instant periodStart;
    private Instant periodEnd;
    
    // Run counts
    private Integer totalRuns;
    private Integer successfulRuns;
    private Integer failedRuns;
    
    // Duration statistics
    private Long avgDurationMs;
    private Long minDurationMs;
    private Long maxDurationMs;
    
    // Average duration in human-readable format
    private String avgDurationFormatted; // e.g., "2h 15m 30s"
    
    // Clock time statistics (CET)
    private BigDecimal avgStartHourCet;
    private BigDecimal avgEndHourCet;
    
    // Formatted clock times
    private String avgStartTimeCet; // e.g., "14:30"
    private String avgEndTimeCet;   // e.g., "16:45"
    
    // SLA tracking
    private Integer slaBreaches;
    private BigDecimal slaComplianceRate; // percentage
}

@Data
@Builder
public class RunDetailResponse {
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private String durationFormatted;
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;
    private String startTimeCetFormatted;
    private String endTimeCetFormatted;
    private String status;
    private Long slaDurationMs;
    private BigDecimal slaEndHourCet;
    private Boolean slaBreached;
    private String slaBreachReason;
    private Map<String, Object> runParameters;
    private Instant createdAt;
    private Instant updatedAt;
}
```

---

## 9. Query Service Implementation

```java
@Service
@Slf4j
public class RunQueryService {
    
    private final CalculatorRunRepository runRepository;
    private final CalculatorRunCustomRepository customRepository;
    private final CalculatorStatisticsRepository statisticsRepository;
    
    public List<RunSummaryResponse> getLastNRuns(String calculatorId, String tenantId, int limit) {
        List<CalculatorRun> runs = runRepository.findLastNRuns(calculatorId, tenantId, limit);
        
        return runs.stream()
            .map(this::toRunSummaryResponse)
            .collect(Collectors.toList());
    }
    
    public AverageRuntimeResponse getAverageRuntime(String calculatorId, String tenantId, int periodDays) {
        // Try to get from pre-computed statistics first
        Optional<CalculatorStatistics> cachedStats = statisticsRepository.findLatestStatistics(
            calculatorId, tenantId, periodDays);
        
        if (cachedStats.isPresent() && 
            cachedStats.get().getComputedAt().isAfter(Instant.now().minus(Duration.ofHours(1)))) {
            return toAverageRuntimeResponse(cachedStats.get());
        }
        
        // Compute on-demand if no recent cached data
        Map<String, Object> stats = customRepository.calculateAverageRuntime(
            calculatorId, tenantId, periodDays);
        
        return buildAverageRuntimeResponse(calculatorId, periodDays, stats);
    }
    
    public RunDetailResponse getRunById(String runId, String tenantId) {
        CalculatorRun run = runRepository.findById(runId)
            .orElseThrow(() -> new RunNotFoundException(runId));
        
        if (!run.getTenantId().equals(tenantId)) {
            throw new TenantAccessDeniedException(tenantId, runId);
        }
        
        return toRunDetailResponse(run);
    }
    
    private RunSummaryResponse toRunSummaryResponse(CalculatorRun run) {
        return RunSummaryResponse.builder()
            .runId(run.getRunId())
            .calculatorId(run.getCalculatorId())
            .calculatorName(run.getCalculatorName())
            .startTime(run.getStartTime())
            .endTime(run.getEndTime())
            .durationMs(run.getDurationMs())
            .startHourCet(run.getStartHourCet())
            .endHourCet(run.getEndHourCet())
            .status(run.getStatus())
            .slaBreached(run.getSlaBreached())
            .slaBreachReason(run.getSlaBreachReason())
            .build();
    }
    
    private RunDetailResponse toRunDetailResponse(CalculatorRun run) {
        return RunDetailResponse.builder()
            .runId(run.getRunId())
            .calculatorId(run.getCalculatorId())
            .calculatorName(run.getCalculatorName())
            .tenantId(run.getTenantId())
            .startTime(run.getStartTime())
            .endTime(run.getEndTime())
            .durationMs(run.getDurationMs())
            .durationFormatted(formatDuration(run.getDurationMs()))
            .startHourCet(run.getStartHourCet())
            .endHourCet(run.getEndHourCet())
            .startTimeCetFormatted(formatCetHour(run.getStartHourCet()))
            .endTimeCetFormatted(formatCetHour(run.getEndHourCet()))
            .status(run.getStatus())
            .slaDurationMs(run.getSlaDurationMs())
            .slaEndHourCet(run.getSlaEndHourCet())
            .slaBreached(run.getSlaBreached())
            .slaBreachReason(run.getSlaBreachReason())
            .runParameters(parseRunParameters(run.getRunParameters()))
            .createdAt(run.getCreatedAt())
            .updatedAt(run.getUpdatedAt())
            .build();
    }
    
    private AverageRuntimeResponse buildAverageRuntimeResponse(
            String calculatorId, int periodDays, Map<String, Object> stats) {
        
        Instant now = Instant.now();
        Instant periodStart = now.minus(Duration.ofDays(periodDays));
        
        Integer totalRuns = (Integer) stats.get("totalRuns");
        Integer slaBreaches = (Integer) stats.get("slaBreaches");
        Integer successfulRuns = totalRuns - slaBreaches;
        
        BigDecimal complianceRate = totalRuns > 0
            ? BigDecimal.valueOf(successfulRuns * 100.0 / totalRuns).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        return AverageRuntimeResponse.builder()
            .calculatorId(calculatorId)
            .periodDays(periodDays)
            .periodStart(periodStart)
            .periodEnd(now)
            .totalRuns(totalRuns)
            .successfulRuns(successfulRuns)
            .failedRuns(slaBreaches)
            .avgDurationMs((Long) stats.get("avgDurationMs"))
            .minDurationMs((Long) stats.get("minDurationMs"))
            .maxDurationMs((Long) stats.get("maxDurationMs"))
            .avgDurationFormatted(formatDuration((Long) stats.get("avgDurationMs")))
            .avgStartHourCet((BigDecimal) stats.get("avgStartHourCet"))
            .avgEndHourCet((BigDecimal) stats.get("avgEndHourCet"))
            .avgStartTimeCet(formatCetHour((BigDecimal) stats.get("avgStartHourCet")))
            .avgEndTimeCet(formatCetHour((BigDecimal) stats.get("avgEndHourCet")))
            .slaBreaches(slaBreaches)
            .slaComplianceRate(complianceRate)
            .build();
    }
    
    private AverageRuntimeResponse toAverageRuntimeResponse(CalculatorStatistics stats) {
        Integer successfulRuns = stats.getTotalRuns() - stats.getSlaBreaches();
        
        BigDecimal complianceRate = stats.getTotalRuns() > 0
            ? BigDecimal.valueOf(successfulRuns * 100.0 / stats.getTotalRuns())
                .setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        return AverageRuntimeResponse.builder()
            .calculatorId(stats.getCalculatorId())
            .periodDays(stats.getPeriodDays())
            .periodStart(stats.getPeriodStart())
            .periodEnd(stats.getPeriodEnd())
            .totalRuns(stats.getTotalRuns())
            .successfulRuns(successfulRuns)
            .failedRuns(stats.getSlaBreaches())
            .avgDurationMs(stats.getAvgDurationMs())
            .minDurationMs(stats.getMinDurationMs())
            .maxDurationMs(stats.getMaxDurationMs())
            .avgDurationFormatted(formatDuration(stats.getAvgDurationMs()))
            .avgStartHourCet(stats.getAvgStartHourCet())
            .avgEndHourCet(stats.getAvgEndHourCet())
            .avgStartTimeCet(formatCetHour(stats.getAvgStartHourCet()))
            .avgEndTimeCet(formatCetHour(stats.getAvgEndHourCet()))
            .slaBreaches(stats.getSlaBreaches())
            .slaComplianceRate(complianceRate)
            .build();
    }
    
    private String formatDuration(Long durationMs) {
        if (durationMs == null) return null;
        
        long hours = durationMs / 3600000;
        long minutes = (durationMs % 3600000) / 60000;
        long seconds = (durationMs % 60000) / 1000;
        
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
    
    private String formatCetHour(BigDecimal hourCet) {
        if (hourCet == null) return null;
        
        int hour = hourCet.intValue();
        int minute = hourCet.subtract(BigDecimal.valueOf(hour))
            .multiply(BigDecimal.valueOf(60))
            .intValue();
        
        return String.format("%02d:%02d", hour, minute);
    }
    
    private Map<String, Object> parseRunParameters(String jsonString) {
        if (jsonString == null) return null;
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(jsonString, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse run parameters", e);
            return null;
        }
    }
}
```

---

## 10. Scheduled Statistics Aggregation

```java
@Component
@Slf4j
@ConditionalOnProperty(value = "observability.statistics.enabled", havingValue = "true", matchIfMissing = true)
public class StatisticsAggregationJob {
    
    private final CalculatorRunCustomRepository customRepository;
    private final CalculatorStatisticsRepository statisticsRepository;
    private final CalculatorRepository calculatorRepository;
    
    @Scheduled(cron = "0 0 2 * * *") // Run daily at 2 AM
    public void aggregateDailyStatistics() {
        log.info("Starting daily statistics aggregation");
        
        List<Calculator> activeCalculators = calculatorRepository.findAllActive();
        
        for (Calculator calculator : activeCalculators) {
            try {
                aggregateStatisticsForCalculator(calculator.getCalculatorId(), 30);
                aggregateStatisticsForCalculator(calculator.getCalculatorId(), 60);
                aggregateStatisticsForCalculator(calculator.getCalculatorId(), 90);
            } catch (Exception e) {
                log.error("Failed to aggregate statistics for calculator {}", 
                    calculator.getCalculatorId(), e);
            }
        }
        
        log.info("Daily statistics aggregation completed");
    }
    
    @Transactional
    protected void aggregateStatisticsForCalculator(String calculatorId, int periodDays) {
        // For simplicity, using a default tenant - adjust based on your multi-tenancy needs
        String tenantId = "default";
        
        Map<String, Object> stats = customRepository.calculateAverageRuntime(
            calculatorId, tenantId, periodDays);
        
        Instant now = Instant.now();
        Instant periodStart = now.minus(Duration.ofDays(periodDays));
        
        CalculatorStatistics statRecord = CalculatorStatistics.builder()
            .calculatorId(calculatorId)
            .tenantId(tenantId)
            .periodDays(periodDays)
            .periodStart(periodStart)
            .periodEnd(now)
            .totalRuns((Integer) stats.get("totalRuns"))
            .successfulRuns(((Integer) stats.get("totalRuns")) - ((Integer) stats.get("slaBreaches")))
            .failedRuns((Integer) stats.get("slaBreaches"))
            .avgDurationMs((Long) stats.get("avgDurationMs"))
            .minDurationMs((Long) stats.get("minDurationMs"))
            .maxDurationMs((Long) stats.get("maxDurationMs"))
            .avgStartHourCet((BigDecimal) stats.get("avgStartHourCet"))
            .avgEndHourCet((BigDecimal) stats.get("avgEndHourCet"))
            .slaBreaches((Integer) stats.get("slaBreaches"))
            .computedAt(now)
            .build();
        
        statisticsRepository.save(statRecord);
        
        log.info("Aggregated statistics for calculator {} over {} days", 
            calculatorId, periodDays);
    }
}
```

---

## 11. Ingestion API DTOs

```java
// Request from Airflow when calculator starts
@Data
@Builder
public class StartRunRequest {
    @NotBlank
    private String runId;
    
    @NotBlank
    private String calculatorId;
    
    @NotNull
    private Instant startTime;
    
    // Run parameters as JSON string
    private String runParameters;
}

// Request from Airflow when calculator completes
@Data
@Builder
public class CompleteRunRequest {
    @NotNull
    private Instant endTime;
    
    private String status; // SUCCESS, FAILED, TIMEOUT (optional, defaults to SUCCESS)
}

// Events
@Getter
@AllArgsConstructor
public class SlaBreachedEvent {
    private final CalculatorRun run;
    private final SlaEvaluationResult result;
}
```

---

## 12. Ingestion Controller (called by Airflow)

```java
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Ingestion", description = "APIs for Airflow to ingest calculator run data")
public class RunIngestionController {
    
    private final RunIngestionService ingestionService;
    
    /**
     * Called by Airflow when calculator starts
     */
    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a calculator run (called by Airflow)")
    public ResponseEntity<RunResponse> startRun(
            @Valid @RequestBody StartRunRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        CalculatorRun run = ingestionService.startRun(request, tenantId);
        
        return ResponseEntity
            .created(URI.create("/api/v1/runs/" + run.getRunId()))
            .body(toRunResponse(run));
    }
    
    /**
     * Called by Airflow when calculator completes
     */
    @PostMapping("/{runId}/complete")
    @Operation(summary = "Complete a calculator run (called by Airflow)")
    public ResponseEntity<RunResponse> completeRun(
            @PathVariable String runId,
            @Valid @RequestBody CompleteRunRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        CalculatorRun run = ingestionService.completeRun(runId, request, tenantId);
        
        return ResponseEntity.ok(toRunResponse(run));
    }
    
    private RunResponse toRunResponse(CalculatorRun run) {
        return RunResponse.builder()
            .runId(run.getRunId())
            .calculatorId(run.getCalculatorId())
            .calculatorName(run.getCalculatorName())
            .status(run.getStatus())
            .startTime(run.getStartTime())
            .endTime(run.getEndTime())
            .durationMs(run.getDurationMs())
            .slaBreached(run.getSlaBreached())
            .slaBreachReason(run.getSlaBreachReason())
            .build();
    }
}

@Data
@Builder
public class RunResponse {
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String status;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private Boolean slaBreached;
    private String slaBreachReason;
}
```

---

## 13. Complete Application Configuration

```yaml
# application.yml
spring:
  application:
    name: observability-service
  
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/${POSTGRES_DB:observability}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
  
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
    show-sql: false
  
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
      password: ${REDIS_PASSWORD:}
      timeout: 3000ms
  
  cache:
    type: redis
    redis:
      time-to-live: 600000 # 10 minutes default

# OpenTelemetry configuration
otel:
  service:
    name: observability-service
  traces:
    exporter: azuremonitor
  metrics:
    exporter: azuremonitor

# Azure Monitor connection
applicationinsights:
  connection:
    string: ${APPLICATIONINSIGHTS_CONNECTION_STRING}

# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always

# Custom properties
observability:
  statistics:
    enabled: true
    aggregation-cron: "0 0 2 * * *"

# Logging
logging:
  level:
    root: INFO
    com.yourcompany.observability: DEBUG
```

---

## 14. Updated pom.xml (Simplified)

```xml
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
    
    <!-- PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
    
    <!-- OpenAPI -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>2.2.0</version>
    </dependency>
    
    <!-- OpenTelemetry -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-spring-boot-starter</artifactId>
        <version>1.32.0-alpha</version>
    </dependency>
    
    <dependency>
        <groupId>com.azure</groupId>
        <artifactId>azure-monitor-opentelemetry-exporter</artifactId>
        <version>1.0.0-beta.13</version>
    </dependency>
    
    <!-- Jackson for JSON -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
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
</dependencies>
```

---

## 15. API Flow Summary

### **Flow 1: Calculator Start (from Airflow)**
```
Airflow → POST /api/v1/runs/start
         ↓
    RunIngestionService.startRun()
         ↓
    - Fetch calculator metadata for SLA
    - Calculate CET hour from start time
    - Create CalculatorRun record with status=RUNNING
    - Save to PostgreSQL
         ↓
    Return 201 Created with run details
```

### **Flow 2: Calculator Complete (from Airflow)**
```
Airflow → POST /api/v1/runs/{runId}/complete
         ↓
    RunIngestionService.completeRun()
         ↓
    - Fetch existing run
    - Calculate duration and CET end hour
    - Update run with end time, duration, status
         ↓
    SlaEvaluationService.evaluateSla()
         ↓
    - Check duration vs SLA target
    - Check end time vs SLA target CET hour
    - Check run status (FAILED/TIMEOUT)
         ↓
    If SLA breached:
        - Update run.slaBreached = true
        - Publish SlaBreachedEvent
              ↓
        AlertHandlerService.handleSlaBreachEvent()
              ↓
        - Create SlaBreachEvent record
        - Send alert to Azure Monitor via OpenTelemetry
         ↓
    Return 200 OK with run details
```

### **Flow 3: Query Last N Runs**
```
UI/Consumer → GET /api/v1/runs/calculator/{id}/recent?limit=10
            ↓
        RunQueryService.getLastNRuns()
            ↓
        - Query PostgreSQL (with Redis cache)
        - Return list of RunSummaryResponse
```

### **Flow 4: Query Average Runtime**
```
UI/Consumer → GET /api/v1/runs/calculator/{id}/average-runtime?periodDays=30
            ↓
        RunQueryService.getAverageRuntime()
            ↓
        - Check CalculatorStatistics table for cached result
        - If cache hit and recent: return cached data
        - If cache miss: compute on-demand from runs table
            ↓
        - Return AverageRuntimeResponse with:
            * Average duration
            * Average start/end CET hours
            * SLA compliance rate
            * Min/max durations
```

---

## Summary

This revised design provides:

1. **Simplified Architecture**: Uses Spring Data JPA with native SQL queries instead of Hibernate
2. **Airflow Integration**: Two REST endpoints for calculator start and complete events
3. **CET Clock Time Analysis**: Automatic conversion and storage of CET hours for time-based analysis
4. **Live SLA Breach Detection**: Real-time evaluation on run completion with immediate alerting
5. **Average Runtime API**: Pre-computed and on-demand statistics for flexible querying
6. **Last N Runs API**: Fast retrieval of recent run history with caching
7. **Azure Monitor Alerting**: OpenTelemetry-based alert delivery for SLA breaches
8. **Production Ready**: Includes caching, scheduled aggregation, error handling, and proper indexing

The system efficiently handles calculator run tracking with a focus on duration and clock time analysis, making it ideal for monitoring and SLA management of scheduled computational jobs.