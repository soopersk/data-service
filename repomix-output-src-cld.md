
# Files

## File: src-cld/main/java/com/company/observability/config/AsyncConfig.java
```java
// File: src/main/java/com/company/observability/config/AsyncConfig.java
package com.company.observability.config;

@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }
}
```

## File: src-cld/main/java/com/company/observability/config/OpenTelemetryConfig.java
```java
// File: src/main/java/com/company/observability/config/OpenTelemetryConfig.java
package com.company.observability.config;

@Configuration
public class OpenTelemetryConfig {
    
    @Bean
    public OpenTelemetry openTelemetry() {
        return AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk();
    }
    
    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("observability-service");
    }
}
```

## File: src-cld/main/java/com/company/observability/config/ParameterNameDiscovererConfig.java
```java
package com.company.observability.config;

@Configuration
public class ParameterNameDiscovererConfig {

    /**
     * Fixes the NoUniqueBeanDefinitionException caused by
     * OpenTelemetry + SpringDoc both defining a ParameterNameDiscoverer.
     */
    @Primary
    @Bean
    public ParameterNameDiscoverer primaryParameterNameDiscoverer(
            ParameterNameDiscoverer parameterNameDiscoverer
    ) {
        // Pick the OpenTelemetry bean as the primary one
        return parameterNameDiscoverer;
    }
}
```

## File: src-cld/main/java/com/company/observability/config/RedisCacheConfig.java
```java
package com.company.observability.config;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        // Configure ObjectMapper for proper JSON serialization
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        // Default cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer)
                )
                .disableCachingNullValues();

        // Specific cache configurations with optimized TTLs
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Recent runs - DAILY frequency (30 min TTL)
        // Calculator runs every 24h, runtime 15-90 min, so 30min cache is perfect
        cacheConfigurations.put("recentRuns:DAILY",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Recent runs - MONTHLY frequency (2 hour TTL)
        // Calculator runs monthly, can cache aggressively
        cacheConfigurations.put("recentRuns:MONTHLY",
                defaultConfig.entryTtl(Duration.ofHours(2)));

        // Batch queries (5 min TTL)
        // Shorter because any calculator change affects the batch
        cacheConfigurations.put("batchRecentRuns",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // Average runtime (30 min TTL)
        cacheConfigurations.put("avgRuntime",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Calculator metadata (24 hour TTL)
        // Rarely changes
        cacheConfigurations.put("calculators",
                defaultConfig.entryTtl(Duration.ofHours(24)));

        // Run details (15 min TTL)
        cacheConfigurations.put("runDetails",
                defaultConfig.entryTtl(Duration.ofMinutes(15)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }
}
```

## File: src-cld/main/java/com/company/observability/controller/HealthController.java
```java
// File: src/main/java/com/company/observability/controller/HealthController.java
package com.company.observability.controller;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Health check endpoints")
public class HealthController {
    
    @GetMapping
    @Operation(summary = "Health check")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now());
        response.put("service", "observability-service");
        response.put("version", "1.0.0");
        
        return ResponseEntity.ok(response);
    }
}
```

## File: src-cld/main/java/com/company/observability/controller/RunIngestionController.java
```java
// File: src/main/java/com/company/observability/controller/RunIngestionController.java
package com.company.observability.controller;

@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Ingestion", description = "APIs for Airflow to ingest calculator run data")
@RequiredArgsConstructor
public class RunIngestionController {
    
    private final RunIngestionService ingestionService;
    
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
```

## File: src-cld/main/java/com/company/observability/controller/RunQueryController.java
```java
// File: src/main/java/com/company/observability/controller/RunQueryController.java
package com.company.observability.controller;

@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Query", description = "Query calculator run information")
@RequiredArgsConstructor
public class RunQueryController {
    
    private final RunQueryService queryService;
    
    @GetMapping("/calculator/{calculatorId}/recent")
    @Operation(summary = "Get last N runs for a calculator")
    public ResponseEntity<LastNRunsResponse> getLastNRuns(
            @PathVariable String calculatorId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        List<RunSummaryResponse> runs = queryService.getLastNRuns(calculatorId, tenantId, limit);
        
        LastNRunsResponse response = LastNRunsResponse.builder()
            .calculatorId(calculatorId)
            .limit(limit)
            .count(runs.size())
            .runs(runs)
            .cachedAt(Instant.now())
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/batch/recent")
    @Operation(summary = "Get recent runs for multiple calculators")
    public ResponseEntity<BatchRecentRunsResponse> getBatchRecentRuns(
            @Valid @RequestBody BatchRecentRunsRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        Map<String, List<RunSummaryResponse>> results = 
            queryService.getBatchRecentRuns(request.getCalculatorIds(), tenantId, request.getLimit());
        
        BatchRecentRunsResponse response = BatchRecentRunsResponse.builder()
            .calculatorCount(request.getCalculatorIds().size())
            .limit(request.getLimit())
            .results(results)
            .queriedAt(Instant.now())
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/calculator/{calculatorId}/average-runtime")
    @Operation(summary = "Get average runtime with frequency-aware lookback")
    public ResponseEntity<AverageRuntimeResponse> getAverageRuntime(
            @PathVariable String calculatorId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        AverageRuntimeResponse response = queryService.getAverageRuntime(calculatorId, tenantId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{runId}")
    @Operation(summary = "Get calculator run by ID")
    public ResponseEntity<RunDetailResponse> getRunById(
            @PathVariable String runId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        RunDetailResponse response = queryService.getRunById(runId, tenantId);
        return ResponseEntity.ok(response);
    }
}
```

## File: src-cld/main/java/com/company/observability/domain/enums/CalculatorFrequency.java
```java
package com.company.observability.domain.enums;

public enum CalculatorFrequency {
    DAILY(2),    // Look back 2 days for DAILY calculators
    MONTHLY(10); // Look back 10 days for MONTHLY calculators

    private final int lookbackDays;

    CalculatorFrequency(int lookbackDays) {
        this.lookbackDays = lookbackDays;
    }

    public int getLookbackDays() {
        return lookbackDays;
    }

    public Duration getLookbackDuration() {
        return Duration.ofDays(lookbackDays);
    }

    public static CalculatorFrequency fromString(String frequency) {
        if (frequency == null) {
            return DAILY;
        }
        try {
            return CalculatorFrequency.valueOf(frequency.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DAILY;
        }
    }
}
```

## File: src-cld/main/java/com/company/observability/domain/enums/RunStatus.java
```java
package com.company.observability.domain.enums;

public enum RunStatus {
    RUNNING("Run is currently in progress"),
    SUCCESS("Run completed successfully"),
    FAILED("Run failed with errors"),
    TIMEOUT("Run exceeded timeout limit"),
    CANCELLED("Run was cancelled");

    private final String description;

    RunStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this != RUNNING;
    }

    public boolean isSuccessful() {
        return this == SUCCESS;
    }

    public static RunStatus fromString(String status) {
        if (status == null) {
            return RUNNING;
        }
        try {
            return RunStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RUNNING;
        }
    }
}
```

## File: src-cld/main/java/com/company/observability/domain/Calculator.java
```java
package com.company.observability.domain;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Calculator {
    private String calculatorId;
    private String name;
    private String description;
    private String frequency; // DAILY or MONTHLY
    private Long slaTargetDurationMs;
    private BigDecimal slaTargetEndHourCet;
    private String ownerTeam;
    private Boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
```

## File: src-cld/main/java/com/company/observability/domain/CalculatorRun.java
```java
package com.company.observability.domain;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorRun {
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private String frequency;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;
    private String status;
    private Long slaDurationMs;
    private BigDecimal slaEndHourCet;
    private Boolean slaBreached;
    private String slaBreachReason;
    private String runParameters; // JSON string
    private Instant createdAt;
    private Instant updatedAt;
}
```

## File: src-cld/main/java/com/company/observability/domain/CalculatorStatistics.java
```java
package com.company.observability.domain;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorStatistics {
    private Long statId;
    private String calculatorId;
    private String tenantId;
    private Integer periodDays;
    private Instant periodStart;
    private Instant periodEnd;
    private Integer totalRuns;
    private Integer successfulRuns;
    private Integer failedRuns;
    private Long avgDurationMs;
    private Long minDurationMs;
    private Long maxDurationMs;
    private BigDecimal avgStartHourCet;
    private BigDecimal avgEndHourCet;
    private Integer slaBreaches;
    private Instant computedAt;
}
```

## File: src-cld/main/java/com/company/observability/domain/SlaBreachEvent.java
```java
package com.company.observability.domain;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaBreachEvent {
    private Long breachId;
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private String breachType;
    private Long expectedValue;
    private Long actualValue;
    private String severity;
    private Boolean alerted;
    private Instant alertedAt;
    private String alertStatus;
    private Instant createdAt;
}
```

## File: src-cld/main/java/com/company/observability/dto/request/BatchRecentRunsRequest.java
```java
package com.company.observability.dto.request;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRecentRunsRequest {
    @NotEmpty(message = "Calculator IDs cannot be empty")
    @Size(max = 100, message = "Maximum 100 calculators per request")
    private List<String> calculatorIds;
    
    @Min(1)
    @Max(50)
    private int limit = 10;
}
```

## File: src-cld/main/java/com/company/observability/dto/request/CompleteRunRequest.java
```java
package com.company.observability.dto.request;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteRunRequest {
    @NotNull(message = "End time is required")
    private Instant endTime;
    
    private String status; // SUCCESS, FAILED, TIMEOUT
}
```

## File: src-cld/main/java/com/company/observability/dto/request/StartRunRequest.java
```java
package com.company.observability.dto.request;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartRunRequest {
    @NotBlank(message = "Run ID is required")
    private String runId;
    
    @NotBlank(message = "Calculator ID is required")
    private String calculatorId;
    
    @NotNull(message = "Start time is required")
    private Instant startTime;
    
    private String runParameters;
}
```

## File: src-cld/main/java/com/company/observability/dto/response/AverageRuntimeResponse.java
```java
package com.company.observability.dto.response;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AverageRuntimeResponse {
    private String calculatorId;
    private String frequency;
    private Integer lookbackDays;
    private Instant periodStart;
    private Instant periodEnd;
    private Integer totalRuns;
    private Integer successfulRuns;
    private Integer failedRuns;
    private Long avgDurationMs;
    private Long minDurationMs;
    private Long maxDurationMs;
    private String avgDurationFormatted;
    private BigDecimal avgStartHourCet;
    private BigDecimal avgEndHourCet;
    private String avgStartTimeCet;
    private String avgEndTimeCet;
    private Integer slaBreaches;
    private BigDecimal slaComplianceRate;
}
```

## File: src-cld/main/java/com/company/observability/dto/response/BatchRecentRunsResponse.java
```java
package com.company.observability.dto.response;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRecentRunsResponse {
    private Integer calculatorCount;
    private Integer limit;
    private Map<String, List<RunSummaryResponse>> results;
    private Instant queriedAt;
}
```

## File: src-cld/main/java/com/company/observability/dto/response/LastNRunsResponse.java
```java
package com.company.observability.dto.response;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LastNRunsResponse {
    private String calculatorId;
    private Integer limit;
    private Integer count;
    private List<RunSummaryResponse> runs;
    private Instant cachedAt;
}
```

## File: src-cld/main/java/com/company/observability/dto/response/RunDetailResponse.java
```java
package com.company.observability.dto.response;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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

## File: src-cld/main/java/com/company/observability/dto/response/RunResponse.java
```java
package com.company.observability.dto.response;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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

## File: src-cld/main/java/com/company/observability/dto/response/RunSummaryResponse.java
```java
package com.company.observability.dto.response;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunSummaryResponse {
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
    private Boolean slaBreached;
    private String slaBreachReason;
    private String frequency;
}
```

## File: src-cld/main/java/com/company/observability/event/RunCompletedEvent.java
```java
package com.company.observability.event;


@Getter
@AllArgsConstructor
public class RunCompletedEvent {
    private final CalculatorRun run;
}
```

## File: src-cld/main/java/com/company/observability/event/RunStartedEvent.java
```java
package com.company.observability.event;


@Getter
@AllArgsConstructor
public class RunStartedEvent {
    private final CalculatorRun run;
}
```

## File: src-cld/main/java/com/company/observability/event/SlaBreachedEvent.java
```java
package com.company.observability.event;


@Getter
@AllArgsConstructor
public class SlaBreachedEvent {
    private final CalculatorRun run;
    private final SlaEvaluationResult result;
}
```

## File: src-cld/main/java/com/company/observability/exception/AlertSendException.java
```java
package com.company.observability.exception;

public class AlertSendException extends RuntimeException {
    public AlertSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## File: src-cld/main/java/com/company/observability/exception/CalculatorNotFoundException.java
```java
package com.company.observability.exception;

public class CalculatorNotFoundException extends RuntimeException {
    public CalculatorNotFoundException(String calculatorId) {
        super("Calculator not found: " + calculatorId);
    }
}
```

## File: src-cld/main/java/com/company/observability/exception/GlobalExceptionHandler.java
```java
package com.company.observability.exception;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(CalculatorNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCalculatorNotFound(CalculatorNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }
    
    @ExceptionHandler(RunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRunNotFound(RunNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }
    
    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleTenantAccessDenied(TenantAccessDeniedException ex) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            errors.put(error.getField(), error.getDefaultMessage())
        );
        
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", Instant.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Validation Failed");
        response.put("errors", errors);
        
        return ResponseEntity.badRequest().body(response);
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
    
    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", Instant.now());
        response.put("status", status.value());
        response.put("error", status.getReasonPhrase());
        response.put("message", message);
        
        return ResponseEntity.status(status).body(response);
    }
}
```

## File: src-cld/main/java/com/company/observability/exception/RunNotFoundException.java
```java
package com.company.observability.exception;

public class RunNotFoundException extends RuntimeException {
    public RunNotFoundException(String runId) {
        super("Run not found: " + runId);
    }
}
```

## File: src-cld/main/java/com/company/observability/exception/TenantAccessDeniedException.java
```java
package com.company.observability.exception;

public class TenantAccessDeniedException extends RuntimeException {
    public TenantAccessDeniedException(String tenantId, String runId) {
        super("Tenant " + tenantId + " does not have access to run " + runId);
    }
}
```

## File: src-cld/main/java/com/company/observability/repository/CalculatorRepository.java
```java
package com.company.observability.repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT_BASE = """
        SELECT calculator_id, name, description, frequency,
               sla_target_duration_ms, sla_target_end_hour_cet,
               owner_team, active, created_at, updated_at
        FROM calculators
        """;

    public Optional<Calculator> findById(String calculatorId) {
        String sql = SELECT_BASE + " WHERE calculator_id = ?";

        List<Calculator> results = jdbcTemplate.query(sql, new CalculatorRowMapper(), calculatorId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Calculator> findAllActive() {
        String sql = SELECT_BASE + " WHERE active = true ORDER BY name";
        return jdbcTemplate.query(sql, new CalculatorRowMapper());
    }

    public Calculator save(Calculator calculator) {
        if (calculator.getCreatedAt() == null) {
            calculator.setCreatedAt(Instant.now());
        }
        calculator.setUpdatedAt(Instant.now());

        String sql = """
            INSERT INTO calculators (
                calculator_id, name, description, frequency,
                sla_target_duration_ms, sla_target_end_hour_cet,
                owner_team, active, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (calculator_id) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                frequency = EXCLUDED.frequency,
                sla_target_duration_ms = EXCLUDED.sla_target_duration_ms,
                sla_target_end_hour_cet = EXCLUDED.sla_target_end_hour_cet,
                owner_team = EXCLUDED.owner_team,
                active = EXCLUDED.active,
                updated_at = EXCLUDED.updated_at
            """;

        jdbcTemplate.update(sql,
                calculator.getCalculatorId(),
                calculator.getName(),
                calculator.getDescription(),
                calculator.getFrequency(),
                calculator.getSlaTargetDurationMs(),
                calculator.getSlaTargetEndHourCet(),
                calculator.getOwnerTeam(),
                calculator.getActive(),
                calculator.getCreatedAt(),
                calculator.getUpdatedAt()
        );

        return calculator;
    }

    private static class CalculatorRowMapper implements RowMapper<Calculator> {
        @Override
        public Calculator mapRow(ResultSet rs, int rowNum) throws SQLException {
            return Calculator.builder()
                    .calculatorId(rs.getString("calculator_id"))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .frequency(rs.getString("frequency"))
                    .slaTargetDurationMs(rs.getLong("sla_target_duration_ms"))
                    .slaTargetEndHourCet(rs.getBigDecimal("sla_target_end_hour_cet"))
                    .ownerTeam(rs.getString("owner_team"))
                    .active(rs.getBoolean("active"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        }
    }
}
```

## File: src-cld/main/java/com/company/observability/repository/CalculatorRunRepository.java
```java
package com.company.observability.repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorRunRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT_BASE = """
        SELECT run_id, calculator_id, calculator_name, tenant_id, frequency,
               start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
               status, sla_duration_ms, sla_end_hour_cet, sla_breached, sla_breach_reason,
               run_parameters, created_at, updated_at
        FROM calculator_runs
        """;

    public Optional<CalculatorRun> findById(String runId) {
        String sql = SELECT_BASE + " WHERE run_id = ?";

        List<CalculatorRun> results = jdbcTemplate.query(sql, new CalculatorRunRowMapper(), runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find recent runs directly from calculator_runs table
     * Optimized with partial index on recent data
     */
    public List<CalculatorRun> findRecentRuns(
            String calculatorId, String tenantId, int limit) {

        String sql = """
        SELECT run_id, calculator_id, calculator_name, tenant_id, frequency,
               start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
               status, sla_duration_ms, sla_end_hour_cet, sla_breached, sla_breach_reason,
               run_parameters, created_at, updated_at
        FROM calculator_runs
        WHERE calculator_id = ? 
        AND tenant_id = ? 
        AND created_at >= NOW() - INTERVAL '30 days'
        ORDER BY created_at DESC
        LIMIT ?
        """;

        return jdbcTemplate.query(sql, new CalculatorRunRowMapper(),
                calculatorId, tenantId, limit);
    }

    /**
     * Batch query for multiple calculators
     * Uses window function to get top N per calculator efficiently
     */
    public List<CalculatorRun> findBatchRecentRuns(
            List<String> calculatorIds, String tenantId, int limit) {

        if (calculatorIds.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",",
                calculatorIds.stream().map(id -> "?").toList());

        String sql = String.format("""
        SELECT * FROM (
            SELECT run_id, calculator_id, calculator_name, tenant_id, frequency,
                   start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                   status, sla_duration_ms, sla_end_hour_cet, sla_breached, sla_breach_reason,
                   run_parameters, created_at, updated_at,
                   ROW_NUMBER() OVER (PARTITION BY calculator_id ORDER BY created_at DESC) as rn
            FROM calculator_runs
            WHERE calculator_id IN (%s)
            AND tenant_id = ?
            AND created_at >= NOW() - INTERVAL '30 days'
        ) ranked
        WHERE rn <= ?
        ORDER BY calculator_id, created_at DESC
        """, placeholders);

        Object[] params = new Object[calculatorIds.size() + 2];
        for (int i = 0; i < calculatorIds.size(); i++) {
            params[i] = calculatorIds.get(i);
        }
        params[calculatorIds.size()] = tenantId;
        params[calculatorIds.size() + 1] = limit;

        return jdbcTemplate.query(sql, new CalculatorRunRowMapper(), params);
    }

    public List<CalculatorRun> findRecentRunsFromMaterializedView(
            String calculatorId, String tenantId, int limit) {

        String sql = """
            SELECT run_id, calculator_id, calculator_name, tenant_id, frequency,
                   start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                   status, sla_breached, sla_breach_reason, created_at,
                   NULL as sla_duration_ms, NULL as sla_end_hour_cet, 
                   NULL as run_parameters, created_at as updated_at
            FROM recent_runs_optimized
            WHERE calculator_id = ? 
            AND tenant_id = ? 
            AND row_num <= ?
            ORDER BY created_at DESC
            """;

        return jdbcTemplate.query(sql, new CalculatorRunRowMapper(),
                calculatorId, tenantId, limit);
    }

    public List<Map<String, Object>> findBatchRecentRunsFromMaterializedView(
            List<String> calculatorIds, String tenantId, int limit) {

        String placeholders = String.join(",", calculatorIds.stream()
                .map(id -> "?").toList());

        String sql = String.format("""
            SELECT run_id, calculator_id, calculator_name, tenant_id, frequency,
                   start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                   status, sla_breached, sla_breach_reason, created_at
            FROM recent_runs_optimized
            WHERE calculator_id IN (%s)
            AND tenant_id = ? 
            AND row_num <= ?
            ORDER BY calculator_id, created_at DESC
            """, placeholders);

        Object[] params = new Object[calculatorIds.size() + 2];
        for (int i = 0; i < calculatorIds.size(); i++) {
            params[i] = calculatorIds.get(i);
        }
        params[calculatorIds.size()] = tenantId;
        params[calculatorIds.size() + 1] = limit;

        return jdbcTemplate.queryForList(sql, params);
    }

    public void upsert(CalculatorRun run) {
        if (run.getCreatedAt() == null) {
            run.setCreatedAt(Instant.now());
        }
        run.setUpdatedAt(Instant.now());

        String sql = """
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency,
                start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                status, sla_duration_ms, sla_end_hour_cet, sla_breached, sla_breach_reason,
                run_parameters, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (run_id) DO UPDATE SET
                end_time = EXCLUDED.end_time,
                duration_ms = EXCLUDED.duration_ms,
                end_hour_cet = EXCLUDED.end_hour_cet,
                status = EXCLUDED.status,
                sla_breached = EXCLUDED.sla_breached,
                sla_breach_reason = EXCLUDED.sla_breach_reason,
                updated_at = EXCLUDED.updated_at
            """;

        jdbcTemplate.update(sql,
                run.getRunId(),
                run.getCalculatorId(),
                run.getCalculatorName(),
                run.getTenantId(),
                run.getFrequency(),
                Timestamp.from(run.getStartTime()),
                run.getEndTime() != null ? Timestamp.from(run.getEndTime()) : null,
                run.getDurationMs(),
                run.getStartHourCet(),
                run.getEndHourCet(),
                run.getStatus(),
                run.getSlaDurationMs(),
                run.getSlaEndHourCet(),
                run.getSlaBreached(),
                run.getSlaBreachReason(),
                run.getRunParameters(),
                Timestamp.from(run.getCreatedAt()),
                Timestamp.from(run.getUpdatedAt())
        );
    }

    public Map<String, Object> calculateAverageRuntime(
            String calculatorId, String tenantId, int lookbackDays) {

        String sql = """
            SELECT 
                COUNT(*) as total_runs,
                COUNT(*) FILTER (WHERE status = 'SUCCESS') as successful_runs,
                COUNT(*) FILTER (WHERE status IN ('FAILED', 'TIMEOUT')) as failed_runs,
                AVG(duration_ms) as avg_duration_ms,
                MIN(duration_ms) as min_duration_ms,
                MAX(duration_ms) as max_duration_ms,
                AVG(start_hour_cet) as avg_start_hour_cet,
                AVG(end_hour_cet) as avg_end_hour_cet,
                COUNT(*) FILTER (WHERE sla_breached = true) as sla_breaches
            FROM calculator_runs
            WHERE calculator_id = ?
            AND tenant_id = ?
            AND status IN ('SUCCESS', 'FAILED', 'TIMEOUT')
            AND created_at >= NOW() - INTERVAL '? days'
            """;

        return jdbcTemplate.queryForMap(sql, calculatorId, tenantId, lookbackDays);
    }

    private static class CalculatorRunRowMapper implements RowMapper<CalculatorRun> {
        @Override
        public CalculatorRun mapRow(ResultSet rs, int rowNum) throws SQLException {
            return CalculatorRun.builder()
                    .runId(rs.getString("run_id"))
                    .calculatorId(rs.getString("calculator_id"))
                    .calculatorName(rs.getString("calculator_name"))
                    .tenantId(rs.getString("tenant_id"))
                    .frequency(rs.getString("frequency"))
                    .startTime(rs.getTimestamp("start_time") != null ?
                            rs.getTimestamp("start_time").toInstant() : null)
                    .endTime(rs.getTimestamp("end_time") != null ?
                            rs.getTimestamp("end_time").toInstant() : null)
                    .durationMs(rs.getObject("duration_ms", Long.class))
                    .startHourCet(rs.getBigDecimal("start_hour_cet"))
                    .endHourCet(rs.getBigDecimal("end_hour_cet"))
                    .status(rs.getString("status"))
                    .slaDurationMs(rs.getObject("sla_duration_ms", Long.class))
                    .slaEndHourCet(rs.getBigDecimal("sla_end_hour_cet"))
                    .slaBreached(rs.getBoolean("sla_breached"))
                    .slaBreachReason(rs.getString("sla_breach_reason"))
                    .runParameters(rs.getString("run_parameters"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        }
    }
}
```

## File: src-cld/main/java/com/company/observability/repository/CalculatorStatisticsRepository.java
```java
package com.company.observability.repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorStatisticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<CalculatorStatistics> findLatestStatistics(
            String calculatorId, String tenantId, int periodDays) {

        String sql = """
            SELECT stat_id, calculator_id, tenant_id, period_days,
                   period_start, period_end, total_runs, successful_runs, failed_runs,
                   avg_duration_ms, min_duration_ms, max_duration_ms,
                   avg_start_hour_cet, avg_end_hour_cet, sla_breaches, computed_at
            FROM calculator_statistics
            WHERE calculator_id = ? 
            AND tenant_id = ? 
            AND period_days = ?
            ORDER BY computed_at DESC
            LIMIT 1
            """;

        List<CalculatorStatistics> results = jdbcTemplate.query(sql,
                new CalculatorStatisticsRowMapper(), calculatorId, tenantId, periodDays);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public CalculatorStatistics save(CalculatorStatistics stats) {
        if (stats.getComputedAt() == null) {
            stats.setComputedAt(Instant.now());
        }

        String sql = """
            INSERT INTO calculator_statistics (
                calculator_id, tenant_id, period_days, period_start, period_end,
                total_runs, successful_runs, failed_runs,
                avg_duration_ms, min_duration_ms, max_duration_ms,
                avg_start_hour_cet, avg_end_hour_cet, sla_breaches, computed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, stats.getCalculatorId());
            ps.setString(2, stats.getTenantId());
            ps.setInt(3, stats.getPeriodDays());
            ps.setTimestamp(4, Timestamp.from(stats.getPeriodStart()));
            ps.setTimestamp(5, Timestamp.from(stats.getPeriodEnd()));
            ps.setInt(6, stats.getTotalRuns());
            ps.setInt(7, stats.getSuccessfulRuns());
            ps.setInt(8, stats.getFailedRuns());
            ps.setObject(9, stats.getAvgDurationMs());
            ps.setObject(10, stats.getMinDurationMs());
            ps.setObject(11, stats.getMaxDurationMs());
            ps.setBigDecimal(12, stats.getAvgStartHourCet());
            ps.setBigDecimal(13, stats.getAvgEndHourCet());
            ps.setInt(14, stats.getSlaBreaches());
            ps.setTimestamp(15, Timestamp.from(stats.getComputedAt()));
            return ps;
        }, keyHolder);

        stats.setStatId(keyHolder.getKey().longValue());
        return stats;
    }

    private static class CalculatorStatisticsRowMapper implements RowMapper<CalculatorStatistics> {
        @Override
        public CalculatorStatistics mapRow(ResultSet rs, int rowNum) throws SQLException {
            return CalculatorStatistics.builder()
                    .statId(rs.getLong("stat_id"))
                    .calculatorId(rs.getString("calculator_id"))
                    .tenantId(rs.getString("tenant_id"))
                    .periodDays(rs.getInt("period_days"))
                    .periodStart(rs.getTimestamp("period_start").toInstant())
                    .periodEnd(rs.getTimestamp("period_end").toInstant())
                    .totalRuns(rs.getInt("total_runs"))
                    .successfulRuns(rs.getInt("successful_runs"))
                    .failedRuns(rs.getInt("failed_runs"))
                    .avgDurationMs(rs.getObject("avg_duration_ms", Long.class))
                    .minDurationMs(rs.getObject("min_duration_ms", Long.class))
                    .maxDurationMs(rs.getObject("max_duration_ms", Long.class))
                    .avgStartHourCet(rs.getBigDecimal("avg_start_hour_cet"))
                    .avgEndHourCet(rs.getBigDecimal("avg_end_hour_cet"))
                    .slaBreaches(rs.getInt("sla_breaches"))
                    .computedAt(rs.getTimestamp("computed_at").toInstant())
                    .build();
        }
    }
}
```

## File: src-cld/main/java/com/company/observability/repository/SlaBreachEventRepository.java
```java
package com.company.observability.repository;


@Repository
@RequiredArgsConstructor
@Slf4j
public class SlaBreachEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<SlaBreachEvent> findUnalertedBreaches(int limit) {
        String sql = """
            SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                   breach_type, expected_value, actual_value, severity,
                   alerted, alerted_at, alert_status, created_at
            FROM sla_breach_events
            WHERE alerted = false
            ORDER BY created_at ASC
            LIMIT ?
            """;

        return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(), limit);
    }

    public SlaBreachEvent save(SlaBreachEvent breach) {
        if (breach.getCreatedAt() == null) {
            breach.setCreatedAt(Instant.now());
        }

        String sql = """
            INSERT INTO sla_breach_events (
                run_id, calculator_id, calculator_name, tenant_id,
                breach_type, expected_value, actual_value, severity,
                alerted, alerted_at, alert_status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, breach.getRunId());
            ps.setString(2, breach.getCalculatorId());
            ps.setString(3, breach.getCalculatorName());
            ps.setString(4, breach.getTenantId());
            ps.setString(5, breach.getBreachType());
            ps.setObject(6, breach.getExpectedValue());
            ps.setObject(7, breach.getActualValue());
            ps.setString(8, breach.getSeverity());
            ps.setBoolean(9, breach.getAlerted());
            ps.setTimestamp(10, breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null);
            ps.setString(11, breach.getAlertStatus());
            ps.setTimestamp(12, Timestamp.from(breach.getCreatedAt()));
            return ps;
        }, keyHolder);

        breach.setBreachId(keyHolder.getKey().longValue());
        return breach;
    }

    public void update(SlaBreachEvent breach) {
        String sql = """
            UPDATE sla_breach_events
            SET alerted = ?,
                alerted_at = ?,
                alert_status = ?
            WHERE breach_id = ?
            """;

        jdbcTemplate.update(sql,
                breach.getAlerted(),
                breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null,
                breach.getAlertStatus(),
                breach.getBreachId()
        );
    }

    private static class SlaBreachEventRowMapper implements RowMapper<SlaBreachEvent> {
        @Override
        public SlaBreachEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return SlaBreachEvent.builder()
                    .breachId(rs.getLong("breach_id"))
                    .runId(rs.getString("run_id"))
                    .calculatorId(rs.getString("calculator_id"))
                    .calculatorName(rs.getString("calculator_name"))
                    .tenantId(rs.getString("tenant_id"))
                    .breachType(rs.getString("breach_type"))
                    .expectedValue(rs.getObject("expected_value", Long.class))
                    .actualValue(rs.getObject("actual_value", Long.class))
                    .severity(rs.getString("severity"))
                    .alerted(rs.getBoolean("alerted"))
                    .alertedAt(rs.getTimestamp("alerted_at") != null ?
                            rs.getTimestamp("alerted_at").toInstant() : null)
                    .alertStatus(rs.getString("alert_status"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .build();
        }
    }
}
```

## File: src-cld/main/java/com/company/observability/scheduled/AlertProcessingJob.java
```java
package com.company.observability.scheduled;


/**
 * Optional: Process pending alerts in batches
 * Useful if real-time event processing fails
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.alert.batch-processing.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class AlertProcessingJob {

    private final SlaBreachEventRepository breachRepository;
    private final AzureMonitorAlertSender alertSender;

    /**
     * Process pending alerts every 5 minutes
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    @Transactional
    public void processPendingAlerts() {
        log.debug("Checking for pending alerts");

        List<SlaBreachEvent> pendingBreaches = breachRepository.findUnalertedBreaches(100);

        if (pendingBreaches.isEmpty()) {
            log.debug("No pending alerts to process");
            return;
        }

        log.info("Processing {} pending alerts", pendingBreaches.size());

        int successCount = 0;
        int failureCount = 0;

        for (SlaBreachEvent breach : pendingBreaches) {
            try {
                // Create a minimal CalculatorRun object for alerting
                com.company.observability.domain.CalculatorRun run =
                        com.company.observability.domain.CalculatorRun.builder()
                                .runId(breach.getRunId())
                                .calculatorId(breach.getCalculatorId())
                                .calculatorName(breach.getCalculatorName())
                                .slaBreachReason(breach.getBreachType())
                                .build();

                alertSender.sendAlert(breach, run);

                breach.setAlerted(true);
                breach.setAlertedAt(Instant.now());
                breach.setAlertStatus("SENT");
                breachRepository.update(breach);

                successCount++;

            } catch (Exception e) {
                log.error("Failed to send alert for breach {}", breach.getBreachId(), e);

                breach.setAlertStatus("FAILED");
                breachRepository.update(breach);

                failureCount++;
            }
        }

        log.info("Alert processing completed: {} succeeded, {} failed",
                successCount, failureCount);
    }
}
```

## File: src-cld/main/java/com/company/observability/scheduled/PartitionManagementJob.java
```java
package com.company.observability.scheduled;

@Component
@Slf4j
@RequiredArgsConstructor
public class PartitionManagementJob {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Create partitions daily at 1 AM
     * Creates partitions for the next 30 days
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void createPartitions() {
        try {
            log.info("Starting partition creation job");

            jdbcTemplate.execute("SELECT create_calculator_run_partitions()");

            log.info("Successfully created calculator_runs partitions");

        } catch (Exception e) {
            log.error("Failed to create partitions", e);
        }
    }

    /**
     * Drop old partitions weekly at 2 AM on Sunday
     * Removes partitions older than 90 days
     */
    @Scheduled(cron = "0 0 2 * * SUN")
    @Transactional
    public void dropOldPartitions() {
        try {
            log.info("Starting old partition cleanup job");

            jdbcTemplate.execute("SELECT drop_old_calculator_run_partitions()");

            log.info("Successfully dropped old calculator_runs partitions");

        } catch (Exception e) {
            log.error("Failed to drop old partitions", e);
        }
    }
}
```

## File: src-cld/main/java/com/company/observability/scheduled/StatisticsAggregationJob.java
```java
package com.company.observability.scheduled;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.statistics.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class StatisticsAggregationJob {

    private final JdbcTemplate jdbcTemplate;
    private final CalculatorRepository calculatorRepository;
    private final CalculatorStatisticsRepository statisticsRepository;

    /**
     * Aggregate statistics daily at 2 AM
     * Computes average runtime, SLA compliance, etc. for all active calculators
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void aggregateDailyStatistics() {
        log.info("Starting daily statistics aggregation");

        List<Calculator> activeCalculators = calculatorRepository.findAllActive();

        int successCount = 0;
        int failureCount = 0;

        for (Calculator calculator : activeCalculators) {
            try {
                aggregateStatisticsForCalculator(calculator.getCalculatorId(), "default");
                successCount++;
            } catch (Exception e) {
                log.error("Failed to aggregate statistics for calculator {}",
                        calculator.getCalculatorId(), e);
                failureCount++;
            }
        }

        log.info("Daily statistics aggregation completed: {} succeeded, {} failed",
                successCount, failureCount);
    }

    @Transactional
    protected void aggregateStatisticsForCalculator(String calculatorId, String tenantId) {
        Calculator calculator = calculatorRepository.findById(calculatorId).orElse(null);
        if (calculator == null) {
            log.warn("Calculator not found: {}", calculatorId);
            return;
        }

        String frequency = calculator.getFrequency();
        int lookbackDays = CalculatorFrequency.valueOf(frequency).getLookbackDays();

        log.debug("Calculating statistics for {} calculator {} (lookback: {} days)",
                frequency, calculatorId, lookbackDays);

        Map<String, Object> stats = calculateStatistics(calculatorId, tenantId, lookbackDays);

        Instant now = Instant.now();
        Instant periodStart = now.minus(Duration.ofDays(lookbackDays));

        CalculatorStatistics statRecord = CalculatorStatistics.builder()
                .calculatorId(calculatorId)
                .tenantId(tenantId)
                .periodDays(lookbackDays)
                .periodStart(periodStart)
                .periodEnd(now)
                .totalRuns(getIntValue(stats, "total_runs"))
                .successfulRuns(getIntValue(stats, "successful_runs"))
                .failedRuns(getIntValue(stats, "failed_runs"))
                .avgDurationMs(getLongValue(stats, "avg_duration_ms"))
                .minDurationMs(getLongValue(stats, "min_duration_ms"))
                .maxDurationMs(getLongValue(stats, "max_duration_ms"))
                .avgStartHourCet(getBigDecimalValue(stats, "avg_start_hour_cet"))
                .avgEndHourCet(getBigDecimalValue(stats, "avg_end_hour_cet"))
                .slaBreaches(getIntValue(stats, "sla_breaches"))
                .build();

        statisticsRepository.save(statRecord);

        log.info("Aggregated statistics for calculator {} over {} days: {} total runs, {} breaches",
                calculatorId, lookbackDays, statRecord.getTotalRuns(), statRecord.getSlaBreaches());
    }

    private Map<String, Object> calculateStatistics(String calculatorId, String tenantId, int lookbackDays) {
        String sql = """
                SELECT 
                    COUNT(*) as total_runs,
                    COUNT(*) FILTER (WHERE status = 'SUCCESS') as successful_runs,
                    COUNT(*) FILTER (WHERE status IN ('FAILED', 'TIMEOUT')) as failed_runs,
                    AVG(duration_ms) as avg_duration_ms,
                    MIN(duration_ms) as min_duration_ms,
                    MAX(duration_ms) as max_duration_ms,
                    AVG(start_hour_cet) as avg_start_hour_cet,
                    AVG(end_hour_cet) as avg_end_hour_cet,
                    COUNT(*) FILTER (WHERE sla_breached = true) as sla_breaches
                FROM calculator_runs
                WHERE calculator_id = ?
                AND tenant_id = ?
                AND status IN ('SUCCESS', 'FAILED', 'TIMEOUT')
                AND created_at >= NOW() - INTERVAL '? days'
                """;

        return jdbcTemplate.queryForMap(sql, calculatorId, tenantId, lookbackDays);
    }

    // Helper methods to safely extract values from Map
    private Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private BigDecimal getBigDecimalValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return null;
    }
}
```

## File: src-cld/main/java/com/company/observability/service/AlertHandlerService.java
```java
// File: src/main/java/com/company/observability/service/AlertHandlerService.java
package com.company.observability.service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AlertHandlerService {
    
    private final SlaBreachEventRepository breachRepository;
    private final AzureMonitorAlertSender azureAlertSender;
    
    @EventListener
    @Async
    @Transactional
    public void handleSlaBreachEvent(SlaBreachedEvent event) {
        CalculatorRun run = event.getRun();
        SlaEvaluationResult result = event.getResult();
        
        log.warn("Processing SLA breach for run {}: {}", run.getRunId(), result.getReason());
        
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
```

## File: src-cld/main/java/com/company/observability/service/AzureMonitorAlertSender.java
```java
// File: src/main/java/com/company/observability/service/AzureMonitorAlertSender.java
package com.company.observability.service;

@Component
@Slf4j
@RequiredArgsConstructor
public class AzureMonitorAlertSender {
    
    private final Tracer tracer;
    
    public void sendAlert(SlaBreachEvent breach, CalculatorRun run) {
        Span span = tracer.spanBuilder("sla.breach.alert")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("calculator.id", breach.getCalculatorId());
            span.setAttribute("calculator.name", breach.getCalculatorName());
            span.setAttribute("tenant.id", breach.getTenantId());
            span.setAttribute("run.id", breach.getRunId());
            span.setAttribute("breach.type", breach.getBreachType());
            span.setAttribute("severity", breach.getSeverity());
            
            span.addEvent("SLA Breach Detected",
                Attributes.of(
                    AttributeKey.stringKey("reason"), run.getSlaBreachReason(),
                    AttributeKey.longKey("expected_duration_ms"), breach.getExpectedValue() != null ? breach.getExpectedValue() : 0L,
                    AttributeKey.longKey("actual_duration_ms"), breach.getActualValue() != null ? breach.getActualValue() : 0L
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

## File: src-cld/main/java/com/company/observability/service/CacheEvictionService.java
```java
// File: src/main/java/com/company/observability/service/CacheEvictionService.java
package com.company.observability.service;


@Service
@Slf4j
@RequiredArgsConstructor
public class CacheEvictionService {
    
    private final CacheManager cacheManager;
    
    @EventListener
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        evictCachesForRun(event.getRun());
    }
    
    @EventListener
    @Async
    public void onRunStarted(RunStartedEvent event) {
        evictCachesForRun(event.getRun());
    }
    
    @EventListener
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        evictCachesForRun(event.getRun());
    }
    
    private void evictCachesForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        String frequency = run.getFrequency();
        
        String cacheName = "recentRuns:" + frequency;
        Cache recentRunsCache = cacheManager.getCache(cacheName);
        
        if (recentRunsCache != null) {
            for (int limit : Arrays.asList(5, 10, 20, 50)) {
                String key = calculatorId + "-" + tenantId + "-" + limit;
                recentRunsCache.evict(key);
            }
            log.debug("Evicted {} cache for calculator {}", cacheName, calculatorId);
        }
        
        Cache batchCache = cacheManager.getCache("batchRecentRuns");
        if (batchCache != null) {
            batchCache.clear();
            log.debug("Cleared batch cache due to update in calculator {}", calculatorId);
        }
        
        Cache avgRuntimeCache = cacheManager.getCache("avgRuntime");
        if (avgRuntimeCache != null) {
            String key = calculatorId + "-" + tenantId;
            avgRuntimeCache.evict(key);
            log.debug("Evicted average runtime cache for calculator {}", calculatorId);
        }
    }
}
```

## File: src-cld/main/java/com/company/observability/service/CacheWarmingService.java
```java
package com.company.observability.service;

/**
 * Automatically warms Redis cache when calculator runs complete
 * This ensures the next UI poll gets immediate cache hit
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.cache.warm-on-completion",
        havingValue = "true",
        matchIfMissing = true
)
public class CacheWarmingService {

    private final CacheManager cacheManager;
    private final CalculatorRunRepository runRepository;
    private final RunQueryService queryService;

    @EventListener
    @Async
    public void onRunStarted(RunStartedEvent event) {
        // Invalidate cache when run starts so status shows RUNNING
        invalidateCacheForRun(event.getRun());
    }

    @EventListener
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        warmCacheForRun(event.getRun());
    }

    @EventListener
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        warmCacheForRun(event.getRun());
    }

    private void warmCacheForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        String frequency = run.getFrequency();

        log.info("Warming cache for calculator {} (frequency: {})", calculatorId, frequency);

        try {
            // Warm cache for common limit values
            warmRecentRunsCache(calculatorId, tenantId, frequency, 10);
            warmRecentRunsCache(calculatorId, tenantId, frequency, 20);
            warmRecentRunsCache(calculatorId, tenantId, frequency, 50);

            // Also warm average runtime cache
            warmAverageRuntimeCache(calculatorId, tenantId, frequency);

            log.info("Successfully warmed cache for calculator {}", calculatorId);

        } catch (Exception e) {
            log.error("Failed to warm cache for calculator {}", calculatorId, e);
        }
    }

    private void invalidateCacheForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        String frequency = run.getFrequency();

        log.debug("Invalidating cache for calculator {} (started)", calculatorId);

        String cacheName = "recentRuns:" + frequency;
        Cache cache = cacheManager.getCache(cacheName);

        if (cache != null) {
            for (int limit : List.of(10, 20, 50)) {
                String key = calculatorId + "-" + tenantId + "-" + limit;
                cache.evict(key);
            }
        }
    }

    private void warmRecentRunsCache(
            String calculatorId, String tenantId, String frequency, int limit) {

        String cacheName = "recentRuns:" + frequency;
        String cacheKey = calculatorId + "-" + tenantId + "-" + limit;

        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.warn("Cache not found: {}", cacheName);
            return;
        }

        // Fetch fresh data
        List<CalculatorRun> runs = runRepository.findRecentRuns(
                calculatorId, tenantId, limit);

        // Convert to DTOs
        List<RunSummaryResponse> responses = runs.stream()
                .map(this::toRunSummaryResponse)
                .collect(Collectors.toList());

        // Put in cache
        cache.put(cacheKey, responses);

        log.debug("Warmed cache: {} -> {} ({} runs)",
                cacheName, cacheKey, responses.size());
    }

    private void warmAverageRuntimeCache(
            String calculatorId, String tenantId, String frequency) {

        String cacheKey = calculatorId + "-" + tenantId;
        Cache cache = cacheManager.getCache("avgRuntime");

        if (cache == null) return;

        try {
            // Calculate fresh average
            AverageRuntimeResponse avgRuntime = queryService.calculateAverageRuntime(
                    calculatorId, tenantId, frequency);

            cache.put(cacheKey, avgRuntime);
            log.debug("Warmed average runtime cache for {}", calculatorId);

        } catch (Exception e) {
            log.warn("Failed to warm average runtime cache", e);
        }
    }

    private RunSummaryResponse toRunSummaryResponse(CalculatorRun run) {
        return RunSummaryResponse.builder()
                .runId(run.getRunId())
                .calculatorId(run.getCalculatorId())
                .calculatorName(run.getCalculatorName())
                .tenantId(run.getTenantId())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .durationMs(run.getDurationMs())
                .durationFormatted(TimeUtils.formatDuration(run.getDurationMs()))
                .startHourCet(run.getStartHourCet())
                .endHourCet(run.getEndHourCet())
                .startTimeCetFormatted(TimeUtils.formatCetHour(run.getStartHourCet()))
                .endTimeCetFormatted(TimeUtils.formatCetHour(run.getEndHourCet()))
                .status(run.getStatus())
                .slaBreached(run.getSlaBreached())
                .slaBreachReason(run.getSlaBreachReason())
                .frequency(run.getFrequency())
                .build();
    }
}
```

## File: src-cld/main/java/com/company/observability/service/RunIngestionService.java
```java
// File: src/main/java/com/company/observability/service/RunIngestionService.java
package com.company.observability.service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunIngestionService {

    private final CalculatorRunRepository runRepository;
    private final CalculatorRepository calculatorRepository;
    private final SlaEvaluationService slaEvaluationService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CalculatorRun startRun(StartRunRequest request, String tenantId) {
        log.info("Starting run {} for calculator {} in tenant {}",
                request.getRunId(), request.getCalculatorId(), tenantId);

        Calculator calculator = calculatorRepository.findById(request.getCalculatorId())
                .orElseThrow(() -> new CalculatorNotFoundException(request.getCalculatorId()));

        CalculatorRun run = CalculatorRun.builder()
                .runId(request.getRunId())
                .calculatorId(request.getCalculatorId())
                .calculatorName(calculator.getName())
                .tenantId(tenantId)
                .frequency(calculator.getFrequency())
                .startTime(request.getStartTime())
                .startHourCet(TimeUtils.calculateCetHour(request.getStartTime()))
                .status("RUNNING")
                .slaDurationMs(calculator.getSlaTargetDurationMs())
                .slaEndHourCet(calculator.getSlaTargetEndHourCet())
                .runParameters(request.getRunParameters())
                .slaBreached(false)
                .build();

        runRepository.upsert(run); // Using upsert method
        eventPublisher.publishEvent(new RunStartedEvent(run));

        log.info("Run {} started for {} calculator at CET hour {}",
                run.getRunId(), calculator.getFrequency(), run.getStartHourCet());

        return run;
    }

    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        log.info("Completing run {} in tenant {}", runId, tenantId);

        CalculatorRun run = runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));

        if (!run.getTenantId().equals(tenantId)) {
            throw new TenantAccessDeniedException(tenantId, runId);
        }

        long durationMs = Duration.between(run.getStartTime(), request.getEndTime()).toMillis();

        run.setEndTime(request.getEndTime());
        run.setDurationMs(durationMs);
        run.setEndHourCet(TimeUtils.calculateCetHour(request.getEndTime()));
        run.setStatus(request.getStatus() != null ? request.getStatus() : "SUCCESS");

        SlaEvaluationResult slaResult = slaEvaluationService.evaluateSla(run);
        run.setSlaBreached(slaResult.isBreached());
        run.setSlaBreachReason(slaResult.getReason());

        runRepository.upsert(run);

        log.info("Run {} completed: duration={}ms, CET end={}, SLA breached={}",
                runId, durationMs, run.getEndHourCet(), slaResult.isBreached());

        if (slaResult.isBreached()) {
            eventPublisher.publishEvent(new SlaBreachedEvent(run, slaResult));
        } else {
            eventPublisher.publishEvent(new RunCompletedEvent(run));
        }

        return run;
    }
}
```

## File: src-cld/main/java/com/company/observability/service/RunQueryService.java
```java
// File: src/main/java/com/company/observability/service/RunQueryService.java
package com.company.observability.service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunQueryService {

    private final CalculatorRunRepository runRepository;
    private final CalculatorRepository calculatorRepository;
    private final ObjectMapper objectMapper;

    public List<RunSummaryResponse> getLastNRuns(String calculatorId, String tenantId, int limit) {
        Calculator calculator = calculatorRepository.findById(calculatorId)
                .orElseThrow(() -> new CalculatorNotFoundException(calculatorId));

        String frequency = calculator.getFrequency();
        return getLastNRunsCached(calculatorId, tenantId, limit, frequency);
    }

    @Cacheable(
            value = "recentRuns:#{#frequency}",
            key = "#calculatorId + '-' + #tenantId + '-' + #limit"
    )
    public List<RunSummaryResponse> getLastNRunsCached(
            String calculatorId, String tenantId, int limit, String frequency) {

        log.debug("Cache miss - fetching from materialized view for calculator {}", calculatorId);

        List<CalculatorRun> runs = runRepository.findRecentRunsFromMaterializedView(
                calculatorId, tenantId, limit);

        return runs.stream()
                .map(this::mapToRunSummaryResponse)
                .collect(Collectors.toList());
    }

    @Cacheable(
            value = "batchRecentRuns",
            key = "#calculatorIds.hashCode() + '-' + #tenantId + '-' + #limit"
    )
    public Map<String, List<RunSummaryResponse>> getBatchRecentRuns(
            List<String> calculatorIds, String tenantId, int limit) {

        log.debug("Batch query for {} calculators", calculatorIds.size());

        List<Map<String, Object>> results = runRepository.findBatchRecentRunsFromMaterializedView(
                calculatorIds, tenantId, limit);

        Map<String, List<RunSummaryResponse>> grouped = new HashMap<>();

        for (Map<String, Object> row : results) {
            String calcId = (String) row.get("calculator_id");
            RunSummaryResponse response = mapRowToRunSummaryResponse(row);
            grouped.computeIfAbsent(calcId, k -> new ArrayList<>()).add(response);
        }

        return grouped;
    }

    @Cacheable(
            value = "avgRuntime",
            key = "#calculatorId + '-' + #tenantId"
    )
    public AverageRuntimeResponse getAverageRuntime(String calculatorId, String tenantId) {
        Calculator calculator = calculatorRepository.findById(calculatorId)
                .orElseThrow(() -> new CalculatorNotFoundException(calculatorId));

        String frequency = calculator.getFrequency();
        int lookbackDays = com.company.observability.domain.enums.CalculatorFrequency
                .valueOf(frequency).getLookbackDays();

        log.debug("Calculating average runtime for {} calculator {} (lookback: {} days)",
                frequency, calculatorId, lookbackDays);

        Map<String, Object> stats = runRepository.calculateAverageRuntime(
                calculatorId, tenantId, lookbackDays);

        return buildAverageRuntimeResponse(calculatorId, stats, frequency, lookbackDays);
    }

    public RunDetailResponse getRunById(String runId, String tenantId) {
        CalculatorRun run = runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));

        if (!run.getTenantId().equals(tenantId)) {
            throw new TenantAccessDeniedException(tenantId, runId);
        }

        return toRunDetailResponse(run);
    }

    private RunSummaryResponse mapToRunSummaryResponse(CalculatorRun run) {
        return RunSummaryResponse.builder()
                .runId(run.getRunId())
                .calculatorId(run.getCalculatorId())
                .calculatorName(run.getCalculatorName())
                .tenantId(run.getTenantId())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .durationMs(run.getDurationMs())
                .durationFormatted(TimeUtils.formatDuration(run.getDurationMs()))
                .startHourCet(run.getStartHourCet())
                .endHourCet(run.getEndHourCet())
                .startTimeCetFormatted(TimeUtils.formatCetHour(run.getStartHourCet()))
                .endTimeCetFormatted(TimeUtils.formatCetHour(run.getEndHourCet()))
                .status(run.getStatus())
                .slaBreached(run.getSlaBreached())
                .slaBreachReason(run.getSlaBreachReason())
                .frequency(run.getFrequency())
                .build();
    }

    private RunSummaryResponse mapRowToRunSummaryResponse(Map<String, Object> row) {
        return RunSummaryResponse.builder()
                .runId((String) row.get("run_id"))
                .calculatorId((String) row.get("calculator_id"))
                .calculatorName((String) row.get("calculator_name"))
                .tenantId((String) row.get("tenant_id"))
                .startTime(((Timestamp) row.get("start_time")).toInstant())
                .endTime(row.get("end_time") != null ? ((Timestamp) row.get("end_time")).toInstant() : null)
                .durationMs(row.get("duration_ms") != null ? ((Number) row.get("duration_ms")).longValue() : null)
                .durationFormatted(row.get("duration_ms") != null ?
                        TimeUtils.formatDuration(((Number) row.get("duration_ms")).longValue()) : null)
                .startHourCet(row.get("start_hour_cet") != null ?
                        new BigDecimal(row.get("start_hour_cet").toString()) : null)
                .endHourCet(row.get("end_hour_cet") != null ?
                        new BigDecimal(row.get("end_hour_cet").toString()) : null)
                .status((String) row.get("status"))
                .slaBreached((Boolean) row.get("sla_breached"))
                .slaBreachReason((String) row.get("sla_breach_reason"))
                .frequency((String) row.get("frequency"))
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
                .durationFormatted(TimeUtils.formatDuration(run.getDurationMs()))
                .startHourCet(run.getStartHourCet())
                .endHourCet(run.getEndHourCet())
                .startTimeCetFormatted(TimeUtils.formatCetHour(run.getStartHourCet()))
                .endTimeCetFormatted(TimeUtils.formatCetHour(run.getEndHourCet()))
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
            String calculatorId, Map<String, Object> stats, String frequency, int lookbackDays) {

        Instant now = Instant.now();
        Instant periodStart = now.minus(Duration.ofDays(lookbackDays));

        Integer totalRuns = getIntValue(stats, "total_runs");
        Integer successfulRuns = getIntValue(stats, "successful_runs");
        Integer failedRuns = getIntValue(stats, "failed_runs");
        Integer slaBreaches = getIntValue(stats, "sla_breaches");

        BigDecimal complianceRate = totalRuns > 0
                ? BigDecimal.valueOf(successfulRuns * 100.0 / totalRuns).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return AverageRuntimeResponse.builder()
                .calculatorId(calculatorId)
                .frequency(frequency)
                .lookbackDays(lookbackDays)
                .periodStart(periodStart)
                .periodEnd(now)
                .totalRuns(totalRuns)
                .successfulRuns(successfulRuns)
                .failedRuns(failedRuns)
                .avgDurationMs(getLongValue(stats, "avg_duration_ms"))
                .minDurationMs(getLongValue(stats, "min_duration_ms"))
                .maxDurationMs(getLongValue(stats, "max_duration_ms"))
                .avgDurationFormatted(TimeUtils.formatDuration(getLongValue(stats, "avg_duration_ms")))
                .avgStartHourCet(getBigDecimalValue(stats, "avg_start_hour_cet"))
                .avgEndHourCet(getBigDecimalValue(stats, "avg_end_hour_cet"))
                .avgStartTimeCet(TimeUtils.formatCetHour(getBigDecimalValue(stats, "avg_start_hour_cet")))
                .avgEndTimeCet(TimeUtils.formatCetHour(getBigDecimalValue(stats, "avg_end_hour_cet")))
                .slaBreaches(slaBreaches)
                .slaComplianceRate(complianceRate)
                .build();
    }

    /**
     * Calculate average runtime (used by cache warming)
     * This is the uncached version
     */
    public AverageRuntimeResponse calculateAverageRuntime(
            String calculatorId, String tenantId, String frequency) {

        int lookbackDays = com.company.observability.domain.enums.CalculatorFrequency
                .valueOf(frequency).getLookbackDays();

        Map<String, Object> stats = runRepository.calculateAverageRuntime(
                calculatorId, tenantId, lookbackDays);

        return buildAverageRuntimeResponse(calculatorId, stats, frequency, lookbackDays);
    }

    private Map<String, Object> parseRunParameters(String jsonString) {
        if (jsonString == null) return null;

        try {
            return objectMapper.readValue(jsonString, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse run parameters", e);
            return null;
        }
    }

    private Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private BigDecimal getBigDecimalValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return null;
    }
}
```

## File: src-cld/main/java/com/company/observability/service/SlaEvaluationService.java
```java
package com.company.observability.service;

@Service
@Slf4j
public class SlaEvaluationService {
    
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
```

## File: src-cld/main/java/com/company/observability/util/SlaEvaluationResult.java
```java
package com.company.observability.util;

@Data
@AllArgsConstructor
public class SlaEvaluationResult {
    private boolean breached;
    private String reason;
    private String severity;
}
```

## File: src-cld/main/java/com/company/observability/util/TimeUtils.java
```java
package com.company.observability.util;

public class TimeUtils {
    
    private static final ZoneId CET_ZONE = ZoneId.of("CET");
    
    public static BigDecimal calculateCetHour(Instant instant) {
        if (instant == null) {
            return null;
        }
        
        ZonedDateTime cetTime = instant.atZone(CET_ZONE);
        double hour = cetTime.getHour() + (cetTime.getMinute() / 60.0);
        return BigDecimal.valueOf(hour).setScale(2, RoundingMode.HALF_UP);
    }
    
    public static String formatDuration(Long durationMs) {
        if (durationMs == null) {
            return null;
        }
        
        long hours = durationMs / 3600000;
        long minutes = (durationMs % 3600000) / 60000;
        long seconds = (durationMs % 60000) / 1000;
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    public static String formatCetHour(BigDecimal hourCet) {
        if (hourCet == null) {
            return null;
        }
        
        int hour = hourCet.intValue();
        int minute = hourCet.subtract(BigDecimal.valueOf(hour))
            .multiply(BigDecimal.valueOf(60))
            .intValue();
        
        return String.format("%02d:%02d", hour, minute);
    }
}
```

## File: src-cld/main/java/com/company/observability/ObservabilityServiceApplication.java
```java
// File: src/main/java/com/company/observability/ObservabilityServiceApplication.java
package com.company.observability;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAsync
public class ObservabilityServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(ObservabilityServiceApplication.class, args);
    }
}
```

## File: src-cld/main/resources/db/migration/V1__initial_schema.sql
```sql
-- File: src/main/resources/db/migration/V1__initial_schema.sql

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Calculators table
CREATE TABLE calculators (
    calculator_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    frequency VARCHAR(20) NOT NULL DEFAULT 'DAILY',
    sla_target_duration_ms BIGINT NOT NULL,
    sla_target_end_hour_cet DECIMAL(5,2),
    owner_team VARCHAR(100),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT chk_frequency CHECK (frequency IN ('DAILY', 'MONTHLY'))
);

CREATE INDEX idx_calculators_active ON calculators(active) WHERE active = TRUE;

-- Calculator runs table (partitioned by created_at)
CREATE TABLE calculator_runs (
    run_id VARCHAR(100) PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(200) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    frequency VARCHAR(20),
    
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    duration_ms BIGINT,
    
    start_hour_cet DECIMAL(5,2),
    end_hour_cet DECIMAL(5,2),
    
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    
    sla_duration_ms BIGINT,
    sla_end_hour_cet DECIMAL(5,2),
    
    sla_breached BOOLEAN DEFAULT FALSE,
    sla_breach_reason VARCHAR(100),
    
    run_parameters TEXT,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT chk_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT'))
);

CREATE INDEX idx_runs_calculator_created ON calculator_runs(calculator_id, created_at DESC);
CREATE INDEX idx_runs_tenant_created ON calculator_runs(tenant_id, created_at DESC);
CREATE INDEX idx_runs_status ON calculator_runs(status);
CREATE INDEX idx_runs_sla_breach ON calculator_runs(calculator_id, sla_breached) WHERE sla_breached = TRUE;

CREATE INDEX idx_runs_daily_recent ON calculator_runs(calculator_id, created_at DESC)
WHERE frequency = 'DAILY';

CREATE INDEX idx_runs_monthly_recent ON calculator_runs(calculator_id, created_at DESC)
WHERE frequency = 'MONTHLY';

-- Calculator statistics table
CREATE TABLE calculator_statistics (
    stat_id BIGSERIAL PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50),
    
    period_days INTEGER NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    
    total_runs INTEGER NOT NULL,
    successful_runs INTEGER NOT NULL,
    failed_runs INTEGER NOT NULL,
    
    avg_duration_ms BIGINT,
    min_duration_ms BIGINT,
    max_duration_ms BIGINT,
    
    avg_start_hour_cet DECIMAL(5,2),
    avg_end_hour_cet DECIMAL(5,2),
    
    sla_breaches INTEGER DEFAULT 0,
    
    computed_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(calculator_id, tenant_id, period_days, period_start)
);

CREATE INDEX idx_stats_calculator_period ON calculator_statistics(calculator_id, period_days);

-- SLA breach events table
CREATE TABLE sla_breach_events (
    breach_id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) NOT NULL,
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(200) NOT NULL,
    tenant_id VARCHAR(50),
    
    breach_type VARCHAR(50) NOT NULL,
    
    expected_value BIGINT,
    actual_value BIGINT,
    
    severity VARCHAR(20) DEFAULT 'MEDIUM',
    
    alerted BOOLEAN DEFAULT FALSE,
    alerted_at TIMESTAMPTZ,
    alert_status VARCHAR(20),
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_breach_calculator ON sla_breach_events(calculator_id, created_at DESC);
CREATE INDEX idx_breach_alerted ON sla_breach_events(alerted) WHERE alerted = FALSE;
```

## File: src-cld/main/resources/db/migration/V3__create_partition_functions.sql
```sql
-- File: src/main/resources/db/migration/V3__create_partition_functions.sql

CREATE OR REPLACE FUNCTION create_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    start_date DATE;
    end_date DATE;
    partition_name TEXT;
BEGIN
    FOR i IN 0..30 LOOP
        start_date := CURRENT_DATE + (i || ' days')::INTERVAL;
        end_date := start_date + INTERVAL '1 day';
        partition_name := 'calculator_runs_' || to_char(start_date, 'YYYY_MM_DD');
        
        IF NOT EXISTS (
            SELECT 1 FROM pg_class WHERE relname = partition_name
        ) THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF calculator_runs 
                FOR VALUES FROM (%L) TO (%L)',
                partition_name, start_date, end_date
            );
            
            RAISE NOTICE 'Created partition %', partition_name;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION drop_old_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    partition_record RECORD;
    retention_date DATE := CURRENT_DATE - INTERVAL '90 days';
BEGIN
    FOR partition_record IN
        SELECT tablename 
        FROM pg_tables 
        WHERE schemaname = 'public' 
        AND tablename LIKE 'calculator_runs_%'
        AND tablename < 'calculator_runs_' || to_char(retention_date, 'YYYY_MM_DD')
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I', partition_record.tablename);
        RAISE NOTICE 'Dropped partition %', partition_record.tablename;
    END LOOP;
END;
$$ LANGUAGE plpgsql;
```