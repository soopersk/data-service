# Files

## File: src/main/java/com/company/observability/cache/RedisCalculatorCache.java
```java
package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.response.CalculatorStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * PHASE 1: Write-through caching with intelligent TTLs
 * Optimized for 60-second polling pattern
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCalculatorCache {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // Cache key prefixes
    private static final String RECENT_RUNS_ZSET = "obs:runs:zset:";  // Sorted set
    private static final String STATUS_RESPONSE = "obs:status:";       // Full response
    private static final String RUNNING_SET = "obs:running";           // Set of running calculator IDs
    private static final String ACTIVE_BLOOM = "obs:active:bloom";     // Bloom filter for existence check

    // ================================================================
    // WRITE-THROUGH: Cache data as it's written to database
    // ================================================================

    /**
     * Cache run in sorted set immediately on write
     * Score = timestamp for automatic ordering
     */
    public void cacheRunOnWrite(CalculatorRun run) {
        try {
            String key = buildRecentRunsKey(run.getCalculatorId(), run.getTenantId());

            // Add to sorted set with timestamp as score
            redisTemplate.opsForZSet().add(
                    key,
                    run,
                    run.getCreatedAt().toEpochMilli()
            );

            // Keep only last 100 runs per calculator
            redisTemplate.opsForZSet().removeRange(key, 0, -101);

            // Dynamic TTL based on status and frequency
            Duration ttl = calculateSmartTTL(run);
            redisTemplate.expire(key, ttl);

            // Track if running
            if ("RUNNING".equals(run.getStatus())) {
                redisTemplate.opsForSet().add(RUNNING_SET,
                        run.getCalculatorId() + ":" + run.getTenantId());
                redisTemplate.expire(RUNNING_SET, Duration.ofHours(2));
            } else {
                redisTemplate.opsForSet().remove(RUNNING_SET,
                        run.getCalculatorId() + ":" + run.getTenantId());
            }

            // Add to bloom filter (for existence checks)
            addToBloomFilter(run.getCalculatorId());

            log.debug("Cached run {} in sorted set with TTL {}",
                    run.getRunId(), ttl);

        } catch (Exception e) {
            log.warn("Failed to cache run on write: {}", e.getMessage());
            // Non-fatal - system works without cache
        }
    }

    /**
     * SMART TTL: Shorter for RUNNING calculators, longer for stable ones
     */
    private Duration calculateSmartTTL(CalculatorRun run) {
        // If calculator is running, data will change soon - short TTL
        if ("RUNNING".equals(run.getStatus())) {
            return Duration.ofMinutes(5);
        }

        // If recently completed, might run again soon
        long minutesSinceCompletion = Duration.between(
                run.getEndTime() != null ? run.getEndTime() : run.getCreatedAt(),
                Instant.now()
        ).toMinutes();

        if (minutesSinceCompletion < 30) {
            return Duration.ofMinutes(15);
        }

        // Stable completed run - long TTL
        return "MONTHLY".equals(run.getFrequency())
                ? Duration.ofHours(4)
                : Duration.ofHours(1);
    }

    // ================================================================
    // READ-THROUGH: Get from cache or signal cache miss
    // ================================================================

    /**
     * Get recent runs from sorted set
     * Returns empty Optional on cache miss (caller queries DB)
     */
    public Optional<List<CalculatorRun>> getRecentRuns(
            String calculatorId, String tenantId, int limit) {

        String key = buildRecentRunsKey(calculatorId, tenantId);

        try {
            // Get top N from sorted set (reverse order = newest first)
            Set<Object> runs = redisTemplate.opsForZSet()
                    .reverseRange(key, 0, limit - 1);

            if (runs == null || runs.isEmpty()) {
                return Optional.empty();
            }

            List<CalculatorRun> result = new ArrayList<>();
            for (Object obj : runs) {
                if (obj instanceof CalculatorRun) {
                    result.add((CalculatorRun) obj);
                }
            }

            log.debug("REDIS HIT: Retrieved {} runs for {}", result.size(), calculatorId);
            return Optional.of(result);

        } catch (Exception e) {
            log.warn("Failed to retrieve from Redis: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Cache full status response (for repeated queries within 60s)
     */
    public void cacheStatusResponse(
            String calculatorId, String tenantId,
            CalculatorStatusResponse response) {

        String key = buildStatusResponseKey(calculatorId, tenantId);

        try {
            // Determine TTL based on current status
            Duration ttl = response.getCurrent().getStatus().equals("RUNNING")
                    ? Duration.ofSeconds(30)  // Shorter for running (will change soon)
                    : Duration.ofSeconds(60); // Longer for completed (stable)

            redisTemplate.opsForValue().set(key, response, ttl);

            log.debug("Cached status response for {} with TTL {}",
                    calculatorId, ttl);

        } catch (Exception e) {
            log.warn("Failed to cache status response: {}", e.getMessage());
        }
    }

    /**
     * Get cached status response
     */
    public Optional<CalculatorStatusResponse> getStatusResponse(
            String calculatorId, String tenantId) {

        String key = buildStatusResponseKey(calculatorId, tenantId);

        try {
            Object cached = redisTemplate.opsForValue().get(key);

            if (cached instanceof CalculatorStatusResponse) {
                log.debug("REDIS HIT: Status response for {}", calculatorId);
                return Optional.of((CalculatorStatusResponse) cached);
            }

            return Optional.empty();

        } catch (Exception e) {
            log.warn("Failed to get status response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Evict status response (when data changes)
     */
    public void evictStatusResponse(String calculatorId, String tenantId) {
        String key = buildStatusResponseKey(calculatorId, tenantId);
        redisTemplate.delete(key);
        log.debug("Evicted status response for {}", calculatorId);
    }

    // ================================================================
    // BATCH OPERATIONS (for dashboard with multiple calculators)
    // ================================================================

    /**
     * Batch get status responses (pipelined for performance)
     */
    public Map<String, CalculatorStatusResponse> getBatchStatusResponses(
            List<String> calculatorIds, String tenantId) {

        Map<String, CalculatorStatusResponse> results = new HashMap<>();

        try {
            // Build all keys
            List<String> keys = new ArrayList<>();
            for (String calcId : calculatorIds) {
                keys.add(buildStatusResponseKey(calcId, tenantId));
            }

            // Pipeline get operations
            List<Object> cachedResponses = redisTemplate.opsForValue()
                    .multiGet(keys);

            // Map results
            for (int i = 0; i < calculatorIds.size(); i++) {
                Object cached = cachedResponses != null ? cachedResponses.get(i) : null;

                if (cached instanceof CalculatorStatusResponse) {
                    results.put(calculatorIds.get(i), (CalculatorStatusResponse) cached);
                }
            }

            log.debug("REDIS BATCH: Retrieved {}/{} status responses",
                    results.size(), calculatorIds.size());

        } catch (Exception e) {
            log.warn("Failed to batch get status responses: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Batch cache status responses (pipelined)
     */
    public void cacheBatchStatusResponses(
            Map<String, CalculatorStatusResponse> responses, String tenantId) {

        try {
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                responses.forEach((calcId, response) -> {
                    String key = buildStatusResponseKey(calcId, tenantId);

                    Duration ttl = response.getCurrent().getStatus().equals("RUNNING")
                            ? Duration.ofSeconds(30)
                            : Duration.ofSeconds(60);

                    redisTemplate.opsForValue().set(key, response, ttl);
                });
                return null;
            });

            log.debug("REDIS BATCH: Cached {} status responses", responses.size());

        } catch (Exception e) {
            log.warn("Failed to batch cache status responses: {}", e.getMessage());
        }
    }

    // ================================================================
    // OPTIMIZATION: Bloom filter for existence checks
    // ================================================================

    /**
     * Check if calculator likely exists (Bloom filter)
     * False positives possible, but no false negatives
     */
    public boolean mightExist(String calculatorId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(ACTIVE_BLOOM, calculatorId)
            );
        } catch (Exception e) {
            return true; // On error, assume exists (safe default)
        }
    }

    /**
     * Add calculator to bloom filter
     */
    private void addToBloomFilter(String calculatorId) {
        try {
            redisTemplate.opsForSet().add(ACTIVE_BLOOM, calculatorId);
            redisTemplate.expire(ACTIVE_BLOOM, Duration.ofHours(24));
        } catch (Exception e) {
            log.warn("Failed to add to bloom filter: {}", e.getMessage());
        }
    }

    /**
     * Check if calculator is currently running
     */
    public boolean isRunning(String calculatorId, String tenantId) {
        try {
            String member = calculatorId + ":" + tenantId;
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(RUNNING_SET, member)
            );
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get all currently running calculators
     */
    public Set<String> getRunningCalculators() {
        try {
            Set<Object> members = redisTemplate.opsForSet().members(RUNNING_SET);

            if (members == null) {
                return Collections.emptySet();
            }

            Set<String> result = new HashSet<>();
            for (Object member : members) {
                result.add(member.toString());
            }

            return result;

        } catch (Exception e) {
            log.warn("Failed to get running calculators: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    // ================================================================
    // Helper methods
    // ================================================================

    private String buildRecentRunsKey(String calculatorId, String tenantId) {
        return RECENT_RUNS_ZSET + calculatorId + ":" + tenantId;
    }

    private String buildStatusResponseKey(String calculatorId, String tenantId) {
        return STATUS_RESPONSE + calculatorId + ":" + tenantId;
    }
}
```

## File: src/main/java/com/company/observability/cache/SlaMonitoringCache.java
```java
package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * LIVE SLA MONITORING using Redis Sorted Set
 * Tracks all running calculators with their SLA deadlines
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaMonitoringCache {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // Sorted set: score = SLA deadline timestamp (epoch millis)
    private static final String SLA_DEADLINES_ZSET = "obs:sla:deadlines";

    // Hash: runId -> minimal run info
    private static final String SLA_RUN_INFO_HASH = "obs:sla:run_info";

    /**
     * Register a calculator run for SLA monitoring
     * Called when run starts
     */
    public void registerForSlaMonitoring(CalculatorRun run) {
        if (run.getSlaTime() == null) {
            log.debug("No SLA time for run {}, skipping monitoring", run.getRunId());
            return;
        }

        if (!"RUNNING".equals(run.getStatus())) {
            log.debug("Run {} not in RUNNING status, skipping SLA monitoring", run.getRunId());
            return;
        }

        try {
            // Calculate score (SLA deadline as epoch millis)
            long slaDeadlineScore = run.getSlaTime().toEpochMilli();

            // Create minimal run info for quick lookups
            Map<String, Object> runInfo = new HashMap<>();
            runInfo.put("runId", run.getRunId());
            runInfo.put("calculatorId", run.getCalculatorId());
            runInfo.put("calculatorName", run.getCalculatorName());
            runInfo.put("tenantId", run.getTenantId());
            runInfo.put("startTime", run.getStartTime().toEpochMilli());
            runInfo.put("slaTime", run.getSlaTime().toEpochMilli());

            String runInfoJson = objectMapper.writeValueAsString(runInfo);

            // Add to sorted set (score = SLA deadline)
            redisTemplate.opsForZSet().add(
                    SLA_DEADLINES_ZSET,
                    run.getRunId(),
                    slaDeadlineScore
            );

            // Store run info in hash for quick retrieval
            redisTemplate.opsForHash().put(
                    SLA_RUN_INFO_HASH,
                    run.getRunId(),
                    runInfoJson
            );

            // Set TTL on both structures (24 hours safety)
            redisTemplate.expire(SLA_DEADLINES_ZSET, Duration.ofHours(24));
            redisTemplate.expire(SLA_RUN_INFO_HASH, Duration.ofHours(24));

            log.debug("Registered run {} for SLA monitoring (deadline: {})",
                    run.getRunId(), run.getSlaTime());

        } catch (Exception e) {
            log.error("Failed to register run for SLA monitoring", e);
        }
    }

    /**
     * Deregister a run (called when run completes)
     */
    public void deregisterFromSlaMonitoring(String runId) {
        try {
            redisTemplate.opsForZSet().remove(SLA_DEADLINES_ZSET, runId);
            redisTemplate.opsForHash().delete(SLA_RUN_INFO_HASH, runId);

            log.debug("Deregistered run {} from SLA monitoring", runId);

        } catch (Exception e) {
            log.error("Failed to deregister run from SLA monitoring", e);
        }
    }

    /**
     * Get all runs that have exceeded their SLA deadline
     * Score range: -∞ to NOW
     */
    public List<Map<String, Object>> getBreachedRuns() {
        try {
            long now = Instant.now().toEpochMilli();

            // Get all runs with SLA deadline <= NOW
            Set<Object> breachedRunIds = redisTemplate.opsForZSet()
                    .rangeByScore(SLA_DEADLINES_ZSET, 0, now);

            if (breachedRunIds == null || breachedRunIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> breachedRuns = new ArrayList<>();

            // Fetch run info for each breached run
            for (Object runIdObj : breachedRunIds) {
                String runId = runIdObj.toString();
                Object runInfoJson = redisTemplate.opsForHash().get(SLA_RUN_INFO_HASH, runId);

                if (runInfoJson != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> runInfo = objectMapper.readValue(
                            runInfoJson.toString(),
                            Map.class
                    );
                    breachedRuns.add(runInfo);
                }
            }

            log.debug("Found {} runs that exceeded SLA deadline", breachedRuns.size());

            return breachedRuns;

        } catch (Exception e) {
            log.error("Failed to get breached runs", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get runs approaching SLA deadline (within next N minutes)
     */
    public List<Map<String, Object>> getApproachingSlaRuns(int minutesAhead) {
        try {
            long now = Instant.now().toEpochMilli();
            long threshold = Instant.now().plus(Duration.ofMinutes(minutesAhead)).toEpochMilli();

            // Get runs with SLA deadline between NOW and NOW+N minutes
            Set<Object> approachingRunIds = redisTemplate.opsForZSet()
                    .rangeByScore(SLA_DEADLINES_ZSET, now, threshold);

            if (approachingRunIds == null || approachingRunIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> approachingRuns = new ArrayList<>();

            for (Object runIdObj : approachingRunIds) {
                String runId = runIdObj.toString();
                Object runInfoJson = redisTemplate.opsForHash().get(SLA_RUN_INFO_HASH, runId);

                if (runInfoJson != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> runInfo = objectMapper.readValue(
                            runInfoJson.toString(),
                            Map.class
                    );
                    approachingRuns.add(runInfo);
                }
            }

            log.debug("Found {} runs approaching SLA deadline (within {} min)",
                    approachingRuns.size(), minutesAhead);

            return approachingRuns;

        } catch (Exception e) {
            log.error("Failed to get approaching SLA runs", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get count of currently monitored runs
     */
    public long getMonitoredRunCount() {
        try {
            Long count = redisTemplate.opsForZSet().size(SLA_DEADLINES_ZSET);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("Failed to get monitored run count", e);
            return 0;
        }
    }

    /**
     * Get next SLA deadline time
     */
    public Optional<Instant> getNextSlaDeadline() {
        try {
            // Get the run with the earliest (smallest score) SLA deadline
            Set<Object> nextRun = redisTemplate.opsForZSet().range(SLA_DEADLINES_ZSET, 0, 0);

            if (nextRun == null || nextRun.isEmpty()) {
                return Optional.empty();
            }

            String runId = nextRun.iterator().next().toString();
            Double score = redisTemplate.opsForZSet().score(SLA_DEADLINES_ZSET, runId);

            if (score != null) {
                return Optional.of(Instant.ofEpochMilli(score.longValue()));
            }

            return Optional.empty();

        } catch (Exception e) {
            log.error("Failed to get next SLA deadline", e);
            return Optional.empty();
        }
    }
}
```

## File: src/main/java/com/company/observability/config/AsyncConfig.java
```java
// File: src/main/java/com/company/observability/config/AsyncConfig.java
package com.company.observability.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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

## File: src/main/java/com/company/observability/config/MetricsConfiguration.java
```java
package com.company.observability.config;

import com.company.observability.repository.CalculatorRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-specific metrics
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class MetricsConfiguration {

    private final CalculatorRunRepository runRepository;

    @Bean
    public MeterBinder customMetrics(MeterRegistry registry) {
        return (reg) -> {
            // Active calculator runs gauge
            Gauge.builder("calculator.runs.active", runRepository, repo -> {
                        try {
                            return repo.countRunning();
                        } catch (Exception e) {
                            log.warn("Failed to count running calculators", e);
                            return 0;
                        }
                    })
                    .description("Number of currently running calculator runs")
                    .register(reg);

            log.info("Custom metrics registered");
        };
    }
}
```

## File: src/main/java/com/company/observability/config/ParameterNameDiscovererConfig.java
```java
package com.company.observability.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterNameDiscoverer;

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

## File: src/main/java/com/company/observability/config/RedisCacheConfig.java
```java
package com.company.observability.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.cache.*;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;

import java.time.Duration;
import java.util.*;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Optimized Lettuce connection factory with connection pooling
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // Socket options for better performance
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .keepAlive(true)
                .build();

        // Client options
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .build();

        // Lettuce client configuration
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(2))
                .build();

        // Redis standalone configuration
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(System.getenv().getOrDefault("REDIS_HOST", "localhost"));
        serverConfig.setPort(Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));

        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) {
            serverConfig.setPassword(password);
        }

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use Jackson serializer for better performance
        Jackson2JsonRedisSerializer<Object> serializer = jackson2JsonRedisSerializer();

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.setEnableTransactionSupport(false); // Better performance
        template.afterPropertiesSet();

        return template;
    }

    /**
     * Multi-tier cache manager with optimized TTLs
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper());

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
                .disableCachingNullValues()
                .prefixCacheNameWith("obs:"); // Namespace prefix

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // ============================================================
        // HOT CACHE: Frequently accessed, short TTL
        // ============================================================

        // Current calculator status (very hot)
        cacheConfigurations.put("calculatorStatus",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // Batch status queries (hot)
        cacheConfigurations.put("batchCalculatorStatus",
                defaultConfig.entryTtl(Duration.ofMinutes(3)));

        // Running calculators count (very hot)
        cacheConfigurations.put("runningCount",
                defaultConfig.entryTtl(Duration.ofMinutes(1)));

        // ============================================================
        // WARM CACHE: Moderate access, medium TTL
        // ============================================================

        // Recent runs by frequency - DAILY (moderate)
        cacheConfigurations.put("recentRuns:DAILY",
                defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // Recent runs by frequency - MONTHLY (less frequent)
        cacheConfigurations.put("recentRuns:MONTHLY",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        // Calculator statistics (moderate)
        cacheConfigurations.put("calculatorStats",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Daily aggregates (moderate)
        cacheConfigurations.put("dailyAggregates",
                defaultConfig.entryTtl(Duration.ofHours(2)));

        // ============================================================
        // COLD CACHE: Rare access, long TTL
        // ============================================================

        // Calculator metadata (extracted from recent run)
        cacheConfigurations.put("calculatorMetadata",
                defaultConfig.entryTtl(Duration.ofHours(6)));

        // Active calculators list
        cacheConfigurations.put("activeCalculators",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        // Historical statistics
        cacheConfigurations.put("historicalStats",
                defaultConfig.entryTtl(Duration.ofHours(12)));

        // ============================================================
        // PERSISTENCE CACHE: Very long TTL for rarely changing data
        // ============================================================

        // SLA configurations (extracted from runs)
        cacheConfigurations.put("slaConfigs",
                defaultConfig.entryTtl(Duration.ofHours(24)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }

    private ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    private Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer() {
        return new Jackson2JsonRedisSerializer<>(objectMapper(), Object.class);
    }
}
```

## File: src/main/java/com/company/observability/config/SecurityConfig.java
```java
package com.company.observability.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Azure AD OAuth2 Security Configuration
 * Configures JWT-based authentication and role-based authorization
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Stateless API - CSRF not needed
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(authz -> authz
                        // Public endpoints
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll() // Prometheus scraping

                        // Ingestion API - Airflow only
                        .requestMatchers("/api/v1/runs/**").hasRole("AIRFLOW")

                        // Query API - UI readers
                        .requestMatchers("/api/v1/calculators/**").hasAnyRole("UI_READER", "AIRFLOW")
                        .requestMatchers("/api/v1/analytics/**").hasAnyRole("UI_READER", "AIRFLOW")

                        // Admin endpoints
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // All other requests require authentication
                        .anyRequest().authenticated()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );

        return http.build();
    }

    /**
     * Convert Azure AD JWT claims to Spring Security authorities
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            // Extract roles from Azure AD token
            // Azure AD sends roles in the "roles" claim
            Collection<String> roles = jwt.getClaimAsStringList("roles");

            if (roles == null) {
                roles = List.of();
            }

            // Convert to Spring Security authorities
            Stream<GrantedAuthority> roleAuthorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role));

            // Also extract standard scopes
            JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();
            Collection<GrantedAuthority> scopeAuthorities = scopesConverter.convert(jwt);

            // Combine roles and scopes
            return Stream.concat(
                    roleAuthorities,
                    scopeAuthorities != null ? scopeAuthorities.stream() : Stream.empty()
            ).collect(Collectors.toList());
        });

        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
                "https://observability-ui.company.com",
                "http://localhost:3000" // Local development
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Request-ID"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
```

## File: src/main/java/com/company/observability/controller/HealthController.java
```java
// File: src/main/java/com/company/observability/controller/HealthController.java
package com.company.observability.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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

## File: src/main/java/com/company/observability/controller/RunIngestionController.java
```java
package com.company.observability.controller;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.request.CompleteRunRequest;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.dto.response.RunResponse;
import com.company.observability.security.TenantContext;
import com.company.observability.service.RunIngestionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * FIXED: Secure ingestion controller with tenant context from JWT
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Ingestion", description = "APIs for Airflow to ingest calculator run data")
@RequiredArgsConstructor
@Slf4j
@SecurityRequirement(name = "bearer-jwt")
public class RunIngestionController {

    private final RunIngestionService ingestionService;
    private final TenantContext tenantContext;
    private final MeterRegistry meterRegistry;

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a calculator run", description = "Called by Airflow when calculator starts")
    @PreAuthorize("hasRole('AIRFLOW')")
    public ResponseEntity<RunResponse> startRun(@Valid @RequestBody StartRunRequest request) {

        // FIXED: Get tenant from JWT, not from header
        String tenantId = tenantContext.getCurrentTenantId();
        String userId = tenantContext.getCurrentUserId();

        log.info("Start run request from user {} for calculator {} in tenant {}",
                userId, request.getCalculatorId(), tenantId);

        meterRegistry.counter("api.runs.start.requests",
                "calculator", request.getCalculatorId()
        ).increment();

        CalculatorRun run = ingestionService.startRun(request, tenantId);

        return ResponseEntity
                .created(URI.create("/api/v1/runs/" + run.getRunId()))
                .body(toRunResponse(run));
    }

    @PostMapping("/{runId}/complete")
    @Operation(summary = "Complete a calculator run", description = "Called by Airflow when calculator finishes")
    @PreAuthorize("hasRole('AIRFLOW')")
    public ResponseEntity<RunResponse> completeRun(
            @PathVariable String runId,
            @Valid @RequestBody CompleteRunRequest request) {

        String tenantId = tenantContext.getCurrentTenantId();
        String userId = tenantContext.getCurrentUserId();

        log.info("Complete run request from user {} for run {} in tenant {}",
                userId, runId, tenantId);

        meterRegistry.counter("api.runs.complete.requests",
                "status", request.getStatus()
        ).increment();

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

## File: src/main/java/com/company/observability/controller/RunQueryController.java
```java
package com.company.observability.controller;

import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.security.TenantContext;
import com.company.observability.service.RunQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/calculators")
@Tag(name = "Calculator Status", description = "Query calculator runtime status and history")
@RequiredArgsConstructor
@Slf4j
@SecurityRequirement(name = "bearer-jwt")
public class RunQueryController {

    private final RunQueryService queryService;
    private final TenantContext tenantContext;
    private final MeterRegistry meterRegistry;

    /**
     * Get calculator status with partition-aware queries
     */
    @GetMapping("/{calculatorId}/status")
    @Operation(
            summary = "Get calculator status with current run and history",
            description = "Returns last N runs based on frequency (DAILY: 2-3 days, MONTHLY: end-of-month)"
    )
    @PreAuthorize("hasAnyRole('UI_READER', 'AIRFLOW')")
    public ResponseEntity<CalculatorStatusResponse> getCalculatorStatus(
            @PathVariable String calculatorId,
            @RequestParam @NotBlank String frequency,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int historyLimit) {

        String tenantId = tenantContext.getCurrentTenantId();

        meterRegistry.counter("api.calculators.status.requests",
                "calculator", calculatorId,
                "frequency", frequency
        ).increment();

        CalculatorStatusResponse response = queryService.getCalculatorStatus(
                calculatorId, tenantId, frequency, historyLimit);

        // Cache for 30 seconds
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                .body(response);
    }

    /**
     * Batch endpoint for dashboard with partition pruning
     */
    @PostMapping("/batch/status")
    @Operation(
            summary = "Get status for multiple calculators",
            description = "Optimized batch query with partition pruning based on frequency"
    )
    @PreAuthorize("hasAnyRole('UI_READER', 'AIRFLOW')")
    public ResponseEntity<List<CalculatorStatusResponse>> getBatchCalculatorStatus(
            @RequestBody @NotEmpty @Size(max = 100) List<String> calculatorIds,
            @RequestParam @NotBlank String frequency,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int historyLimit,
            @Parameter(description = "Use stale cache (faster, may be slightly outdated)")
            @RequestParam(defaultValue = "false") boolean allowStale) {

        String tenantId = tenantContext.getCurrentTenantId();

        log.debug("Batch status query for {} calculators (frequency={}, allowStale={})",
                calculatorIds.size(), frequency, allowStale);

        meterRegistry.counter("api.calculators.batch.requests",
                "count", String.valueOf(calculatorIds.size()),
                "frequency", frequency,
                "allow_stale", String.valueOf(allowStale)
        ).increment();

        List<CalculatorStatusResponse> response = queryService.getBatchCalculatorStatus(
                calculatorIds, tenantId, frequency, historyLimit);

        // Aggressive caching for batch queries when stale is allowed
        CacheControl cacheControl = allowStale
                ? CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate()
                : CacheControl.maxAge(15, TimeUnit.SECONDS).cachePrivate();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .body(response);
    }
}
```

## File: src/main/java/com/company/observability/domain/enums/AlertStatus.java
```java
package com.company.observability.domain.enums;

public enum AlertStatus {
    PENDING("Alert is pending to be sent"),
    SENT("Alert has been sent successfully"),
    FAILED("Alert sending failed"),
    RETRYING("Retrying to send alert");

    private final String description;

    AlertStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isFinal() {
        return this == SENT || this == FAILED;
    }

    public static AlertStatus fromString(String status) {
        if (status == null) {
            return PENDING;
        }
        try {
            return AlertStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
```

## File: src/main/java/com/company/observability/domain/enums/BreachType.java
```java
package com.company.observability.domain.enums;

public enum BreachType {
    DURATION_EXCEEDED("Run duration exceeded SLA target"),
    TIME_EXCEEDED("Run end time exceeded SLA target"),
    FAILED("Run failed"),
    TIMEOUT("Run timed out"),
    UNKNOWN("Unknown breach type");

    private final String description;

    BreachType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static BreachType fromString(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        try {
            return BreachType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
```

## File: src/main/java/com/company/observability/domain/enums/CalculatorFrequency.java
```java
package com.company.observability.domain.enums;

import java.time.Duration;

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

## File: src/main/java/com/company/observability/domain/enums/RunStatus.java
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

## File: src/main/java/com/company/observability/domain/enums/Severity.java
```java
package com.company.observability.domain.enums;

public enum Severity {
    LOW(1, "Low severity - minor issue"),
    MEDIUM(2, "Medium severity - requires attention"),
    HIGH(3, "High severity - urgent attention needed"),
    CRITICAL(4, "Critical severity - immediate action required");

    private final int level;
    private final String description;

    Severity(int level, String description) {
        this.level = level;
        this.description = description;
    }

    public int getLevel() {
        return level;
    }

    public String getDescription() {
        return description;
    }

    public boolean isHigherThan(Severity other) {
        return this.level > other.level;
    }

    public static Severity fromString(String severity) {
        if (severity == null) {
            return MEDIUM;
        }
        try {
            return Severity.valueOf(severity.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}
```

## File: src/main/java/com/company/observability/domain/CalculatorRun.java
```java
package com.company.observability.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Calculator Run domain model with reporting_date for partition key
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorRun implements Serializable {
    private static final long serialVersionUID = 1L;

    // Primary key
    private String runId;

    // Calculator metadata
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private String frequency; // DAILY or MONTHLY

    // Partition key - critical for query performance
    private LocalDate reportingDate;

    // Timing information
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;

    // CET time conversions for display
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;

    // Status
    private String status; // RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED

    // SLA tracking
    private Instant slaTime;
    private Long expectedDurationMs;
    private Instant estimatedStartTime;
    private Instant estimatedEndTime;

    private Boolean slaBreached;
    private String slaBreachReason;

    // Metadata
    private String runParameters;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Helper to determine if this is a DAILY run
     */
    public boolean isDaily() {
        return "DAILY".equalsIgnoreCase(frequency);
    }

    /**
     * Helper to determine if this is a MONTHLY run
     */
    public boolean isMonthly() {
        return "MONTHLY".equalsIgnoreCase(frequency);
    }

    /**
     * Helper to check if this is an end-of-month reporting date
     */
    public boolean isEndOfMonth() {
        if (reportingDate == null) return false;
        LocalDate nextDay = reportingDate.plusDays(1);
        return nextDay.getMonth() != reportingDate.getMonth();
    }
}
```

## File: src/main/java/com/company/observability/domain/DailyAggregate.java
```java
package com.company.observability.domain;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * GT Enhancement: Daily aggregated metrics per calculator
 * Pre-computed for fast dashboard queries
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyAggregate implements Serializable {
    private static final long serialVersionUID = 1L;

    private String calculatorId;
    private String tenantId;
    private LocalDate dayCet;
    private Integer totalRuns;
    private Integer successRuns;
    private Integer slaBreaches;
    private Long avgDurationMs;
    private Integer avgStartMinCet;  // Minutes since midnight CET (0-1439)
    private Integer avgEndMinCet;    // Minutes since midnight CET (0-1439)
    private Instant computedAt;
}
```

## File: src/main/java/com/company/observability/domain/SlaBreachEvent.java
```java
package com.company.observability.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
    private Integer retryCount;
    private String lastError;
    private Instant createdAt;
}
```

## File: src/main/java/com/company/observability/dto/request/CompleteRunRequest.java
```java
package com.company.observability.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

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

## File: src/main/java/com/company/observability/dto/request/StartRunRequest.java
```java
package com.company.observability.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request to start a calculator run with reporting_date
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartRunRequest {
    @NotBlank(message = "Run ID is required")
    private String runId;

    @NotBlank(message = "Calculator ID is required")
    private String calculatorId;

    @NotBlank(message = "Calculator name is required")
    private String calculatorName;

    @NotBlank(message = "Frequency is required (DAILY or MONTHLY)")
    private String frequency;

    @NotNull(message = "Reporting date is required")
    private LocalDate reportingDate;

    @NotNull(message = "Start time is required")
    private Instant startTime;

    @NotNull(message = "SLA time (CET) is required")
    private LocalTime slaTimeCet;  // e.g., "06:15:00"

    // Optional fields
    private Long expectedDurationMs;
    private LocalTime estimatedStartTimeCet;
    private String runParameters;
}
```

## File: src/main/java/com/company/observability/dto/response/CalculatorStatusResponse.java
```java
package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * New response format matching the requirement
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorStatusResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private String calculatorName;
    private Instant lastRefreshed;
    private RunStatusInfo current;
    private List<RunStatusInfo> history;
}
```

## File: src/main/java/com/company/observability/dto/response/RunResponse.java
```java
package com.company.observability.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

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

## File: src/main/java/com/company/observability/dto/response/RunStatusInfo.java
```java
package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunStatusInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String runId;
    private String status; // RUNNING, COMPLETED, FAILED, TIMEOUT, NOT_STARTED
    private Instant start;
    private Instant end;
    private Instant estimatedStart;
    private Instant estimatedEnd;
    private Instant sla; // Absolute SLA deadline time

    // Additional useful attributes
    private Long durationMs;
    private String durationFormatted;
    private Boolean slaBreached;
    private String slaBreachReason;

}
```

## File: src/main/java/com/company/observability/event/RunCompletedEvent.java
```java
package com.company.observability.event;

import com.company.observability.domain.CalculatorRun;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RunCompletedEvent {
    private final CalculatorRun run;
}
```

## File: src/main/java/com/company/observability/event/RunStartedEvent.java
```java
package com.company.observability.event;

import com.company.observability.domain.CalculatorRun;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RunStartedEvent {
    private final CalculatorRun run;
}
```

## File: src/main/java/com/company/observability/event/SlaBreachedEvent.java
```java
package com.company.observability.event;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.util.SlaEvaluationResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SlaBreachedEvent {
    private final CalculatorRun run;
    private final SlaEvaluationResult result;
}
```

## File: src/main/java/com/company/observability/exception/AlertSendException.java
```java
package com.company.observability.exception;

public class AlertSendException extends RuntimeException {
    public AlertSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## File: src/main/java/com/company/observability/exception/CalculatorNotFoundException.java
```java
package com.company.observability.exception;

public class CalculatorNotFoundException extends RuntimeException {
    public CalculatorNotFoundException(String calculatorId) {
        super("Calculator not found: " + calculatorId);
    }
}
```

## File: src/main/java/com/company/observability/exception/GlobalExceptionHandler.java
```java
package com.company.observability.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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

## File: src/main/java/com/company/observability/exception/RunNotFoundException.java
```java
package com.company.observability.exception;

public class RunNotFoundException extends RuntimeException {
    public RunNotFoundException(String runId) {
        super("Run not found: " + runId);
    }
}
```

## File: src/main/java/com/company/observability/exception/TenantAccessDeniedException.java
```java
package com.company.observability.exception;

public class TenantAccessDeniedException extends RuntimeException {
    public TenantAccessDeniedException(String tenantId, String runId) {
        super("Tenant " + tenantId + " does not have access to run " + runId);
    }
}
```

## File: src/main/java/com/company/observability/repository/CalculatorRunRepository.java
```java
package com.company.observability.repository;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Partition-aware repository optimized for reporting_date queries
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RedisCalculatorCache redisCache;

    private static final String SELECT_BASE = """
        SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
               start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
               status, sla_time, expected_duration_ms, 
               estimated_start_time, estimated_end_time,
               sla_breached, sla_breach_reason,
               run_parameters, created_at, updated_at
        FROM calculator_runs
        """;

    /**
     * Find recent runs with partition pruning
     * DAILY: reporting_date in last 2-3 days
     * MONTHLY: reporting_date = end of month
     */
    public List<CalculatorRun> findRecentRuns(
            String calculatorId, String tenantId, String frequency, int limit) {

        // Check bloom filter
        if (!redisCache.mightExist(calculatorId)) {
            log.debug("Bloom filter miss for calculator {}", calculatorId);
            return queryAndCacheRecentRuns(calculatorId, tenantId, frequency, limit);
        }

        // Try Redis sorted set
        Optional<List<CalculatorRun>> cached = redisCache.getRecentRuns(
                calculatorId, tenantId, limit);

        if (cached.isPresent()) {
            log.debug("REDIS HIT: Recent runs for {}", calculatorId);
            return cached.get();
        }

        // Cache miss - query database
        log.debug("REDIS MISS: Querying database for {}", calculatorId);
        return queryAndCacheRecentRuns(calculatorId, tenantId, frequency, limit);
    }

    /**
     * Query with partition pruning based on frequency
     */
    private List<CalculatorRun> queryAndCacheRecentRuns(
            String calculatorId, String tenantId, String frequency, int limit) {

        String sql = buildPartitionPrunedQuery(frequency);

        List<CalculatorRun> runs = jdbcTemplate.query(
                sql,
                new CalculatorRunRowMapper(),
                calculatorId, tenantId, frequency, limit
        );

        // Populate Redis cache
        if (!runs.isEmpty()) {
            runs.forEach(redisCache::cacheRunOnWrite);
            log.debug("Populated Redis cache with {} runs for {}", runs.size(), calculatorId);
        }

        return runs;
    }

    /**
     * Build SQL with optimal partition pruning
     */
    private String buildPartitionPrunedQuery(String frequency) {
        if ("DAILY".equalsIgnoreCase(frequency)) {
            // DAILY: Last 3 days only (prunes to 3 partitions)
            return SELECT_BASE + """
                WHERE calculator_id = ? 
                AND tenant_id = ? 
                AND frequency = ?
                AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'
                AND reporting_date <= CURRENT_DATE
                ORDER BY reporting_date DESC, created_at DESC
                LIMIT ?
                """;
        } else {
            // MONTHLY: End of month dates only in last 13 months (prunes to ~13 partitions)
            return SELECT_BASE + """
                WHERE calculator_id = ? 
                AND tenant_id = ? 
                AND frequency = ?
                AND reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE
                AND reporting_date >= CURRENT_DATE - INTERVAL '13 months'
                ORDER BY reporting_date DESC, created_at DESC
                LIMIT ?
                """;
        }
    }

    /**
     * Batch query with partition pruning
     */
    public Map<String, List<CalculatorRun>> findBatchRecentRuns(
            List<String> calculatorIds, String tenantId, String frequency, int limit) {

        if (calculatorIds == null || calculatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<CalculatorRun>> result = new HashMap<>();
        List<String> cacheMisses = new ArrayList<>();

        // Check Redis for each calculator
        for (String calculatorId : calculatorIds) {
            Optional<List<CalculatorRun>> cached = redisCache.getRecentRuns(
                    calculatorId, tenantId, limit);

            if (cached.isPresent()) {
                result.put(calculatorId, cached.get());
            } else {
                cacheMisses.add(calculatorId);
            }
        }

        log.debug("BATCH: {} Redis hits, {} misses", result.size(), cacheMisses.size());

        // Query database for cache misses
        if (!cacheMisses.isEmpty()) {
            Map<String, List<CalculatorRun>> dbResults = queryBatchFromDatabase(
                    cacheMisses, tenantId, frequency, limit);

            // Cache the results
            dbResults.forEach((calcId, runs) -> {
                runs.forEach(redisCache::cacheRunOnWrite);
            });

            result.putAll(dbResults);
        }

        return result;
    }

    /**
     * Single optimized batch query with partition pruning
     */
    private Map<String, List<CalculatorRun>> queryBatchFromDatabase(
            List<String> calculatorIds, String tenantId, String frequency, int limit) {

        String placeholders = String.join(",", Collections.nCopies(calculatorIds.size(), "?"));
        String partitionFilter = buildPartitionFilter(frequency);

        String sql = String.format("""
            SELECT * FROM (
                SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                       start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                       status, sla_time, expected_duration_ms,
                       estimated_start_time, estimated_end_time,
                       sla_breached, sla_breach_reason,
                       run_parameters, created_at, updated_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY calculator_id 
                           ORDER BY reporting_date DESC, created_at DESC
                       ) as rn
                FROM calculator_runs
                WHERE calculator_id IN (%s)
                AND tenant_id = ?
                AND frequency = ?
                %s
            ) ranked
            WHERE rn <= ?
            ORDER BY calculator_id, reporting_date DESC, created_at DESC
            """, placeholders, partitionFilter);

        Object[] params = new Object[calculatorIds.size() + 3];
        for (int i = 0; i < calculatorIds.size(); i++) {
            params[i] = calculatorIds.get(i);
        }
        params[calculatorIds.size()] = tenantId;
        params[calculatorIds.size() + 1] = frequency;
        params[calculatorIds.size() + 2] = limit;

        List<CalculatorRun> allRuns = jdbcTemplate.query(sql, new CalculatorRunRowMapper(), params);

        // Group by calculator ID
        Map<String, List<CalculatorRun>> grouped = new HashMap<>();
        for (CalculatorRun run : allRuns) {
            grouped.computeIfAbsent(run.getCalculatorId(), k -> new ArrayList<>()).add(run);
        }

        return grouped;
    }

    /**
     * Build partition filter based on frequency
     */
    private String buildPartitionFilter(String frequency) {
        if ("DAILY".equalsIgnoreCase(frequency)) {
            return """
                AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'
                AND reporting_date <= CURRENT_DATE
                """;
        } else {
            return """
                AND reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE
                AND reporting_date >= CURRENT_DATE - INTERVAL '13 months'
                """;
        }
    }

    /**
     * Write-through upsert with partition key
     */
    public CalculatorRun upsert(CalculatorRun run) {
        if (run.getCreatedAt() == null) {
            run.setCreatedAt(Instant.now());
        }
        run.setUpdatedAt(Instant.now());

        String sql = """
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                status, sla_time, expected_duration_ms,
                estimated_start_time, estimated_end_time,
                sla_breached, sla_breach_reason,
                run_parameters, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (run_id, reporting_date) DO UPDATE SET
                end_time = EXCLUDED.end_time,
                duration_ms = EXCLUDED.duration_ms,
                end_hour_cet = EXCLUDED.end_hour_cet,
                status = EXCLUDED.status,
                sla_breached = EXCLUDED.sla_breached,
                sla_breach_reason = EXCLUDED.sla_breach_reason,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """;

        try {
            CalculatorRun savedRun = jdbcTemplate.queryForObject(sql, new CalculatorRunRowMapper(),
                    run.getRunId(),
                    run.getCalculatorId(),
                    run.getCalculatorName(),
                    run.getTenantId(),
                    run.getFrequency(),
                    run.getReportingDate(),
                    run.getStartTime() != null ? Timestamp.from(run.getStartTime()) : null,
                    run.getEndTime() != null ? Timestamp.from(run.getEndTime()) : null,
                    run.getDurationMs(),
                    run.getStartHourCet(),
                    run.getEndHourCet(),
                    run.getStatus(),
                    run.getSlaTime() != null ? Timestamp.from(run.getSlaTime()) : null,
                    run.getExpectedDurationMs(),
                    run.getEstimatedStartTime() != null ? Timestamp.from(run.getEstimatedStartTime()) : null,
                    run.getEstimatedEndTime() != null ? Timestamp.from(run.getEstimatedEndTime()) : null,
                    run.getSlaBreached(),
                    run.getSlaBreachReason(),
                    run.getRunParameters(),
                    Timestamp.from(run.getCreatedAt()),
                    Timestamp.from(run.getUpdatedAt())
            );

            // Write-through cache
            redisCache.cacheRunOnWrite(savedRun);

            log.debug("Upserted and cached run {}", savedRun.getRunId());

            return savedRun;

        } catch (Exception e) {
            log.error("Failed to upsert run {}", run.getRunId(), e);
            throw new RuntimeException("Failed to save calculator run", e);
        }
    }

    /**
     * Find by run_id with partition key hint
     */
    public Optional<CalculatorRun> findById(String runId, LocalDate reportingDate) {
        String sql = SELECT_BASE + " WHERE run_id = ? AND reporting_date = ?";
        List<CalculatorRun> results = jdbcTemplate.query(
                sql, new CalculatorRunRowMapper(), runId, reportingDate);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find by run_id without partition key (slower - scans multiple partitions)
     */
    public Optional<CalculatorRun> findById(String runId) {
        String sql = SELECT_BASE + " WHERE run_id = ? ORDER BY reporting_date DESC LIMIT 1";
        List<CalculatorRun> results = jdbcTemplate.query(sql, new CalculatorRunRowMapper(), runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Mark SLA breached with partition awareness
     */
    public int markSlaBreached(String runId, String breachReason, LocalDate reportingDate) {
        return jdbcTemplate.update("""
            UPDATE calculator_runs
            SET sla_breached = true,
                sla_breach_reason = ?,
                updated_at = NOW()
            WHERE run_id = ? 
              AND reporting_date = ?
              AND status = 'RUNNING'
              AND sla_breached = false
            """, breachReason, runId, reportingDate);
    }

    /**
     * Count running calculators (recent partitions only)
     */
    public int countRunning() {
        // Try Redis first
        Set<String> running = redisCache.getRunningCalculators();
        if (!running.isEmpty()) {
            return running.size();
        }

        // Fallback to database (recent partitions only)
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM calculator_runs 
            WHERE status = 'RUNNING'
            AND reporting_date >= CURRENT_DATE - INTERVAL '7 days'
            """, Integer.class);

        return count != null ? count : 0;
    }

    /**
     * Get partition statistics for monitoring
     */
    public List<Map<String, Object>> getPartitionStatistics() {
        return jdbcTemplate.queryForList("SELECT * FROM get_partition_statistics()");
    }

    private static class CalculatorRunRowMapper implements RowMapper<CalculatorRun> {
        @Override
        public CalculatorRun mapRow(ResultSet rs, int rowNum) {
            try {
                return CalculatorRun.builder()
                        .runId(rs.getString("run_id"))
                        .calculatorId(rs.getString("calculator_id"))
                        .calculatorName(rs.getString("calculator_name"))
                        .tenantId(rs.getString("tenant_id"))
                        .frequency(rs.getString("frequency"))
                        .reportingDate(rs.getObject("reporting_date", LocalDate.class))
                        .startTime(getInstant(rs, "start_time"))
                        .endTime(getInstant(rs, "end_time"))
                        .durationMs(rs.getObject("duration_ms", Long.class))
                        .startHourCet(rs.getBigDecimal("start_hour_cet"))
                        .endHourCet(rs.getBigDecimal("end_hour_cet"))
                        .status(rs.getString("status"))
                        .slaTime(getInstant(rs, "sla_time"))
                        .expectedDurationMs(rs.getObject("expected_duration_ms", Long.class))
                        .estimatedStartTime(getInstant(rs, "estimated_start_time"))
                        .estimatedEndTime(getInstant(rs, "estimated_end_time"))
                        .slaBreached(rs.getBoolean("sla_breached"))
                        .slaBreachReason(rs.getString("sla_breach_reason"))
                        .runParameters(rs.getString("run_parameters"))
                        .createdAt(getInstant(rs, "created_at"))
                        .updatedAt(getInstant(rs, "updated_at"))
                        .build();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map calculator run", e);
            }
        }

        private static Instant getInstant(ResultSet rs, String columnName) throws SQLException {
            Timestamp timestamp = rs.getTimestamp(columnName);
            return timestamp != null ? timestamp.toInstant() : null;
        }
    }
}
```

## File: src/main/java/com/company/observability/repository/DailyAggregateRepository.java
```java
package com.company.observability.repository;

import com.company.observability.domain.DailyAggregate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Daily aggregate repository with reporting_date alignment
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class DailyAggregateRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Atomic upsert using reporting_date (matches partition key)
     */
    public void upsertDaily(String calculatorId, String tenantId, LocalDate reportingDate,
                            String status, boolean slaBreached, long durationMs,
                            int startMinCet, int endMinCet) {
        String sql = """
            INSERT INTO calculator_sli_daily (
                calculator_id, tenant_id, day_cet,
                total_runs, success_runs, sla_breaches,
                avg_duration_ms, avg_start_min_cet, avg_end_min_cet, computed_at
            ) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (calculator_id, tenant_id, day_cet)
            DO UPDATE SET
                total_runs = calculator_sli_daily.total_runs + 1,
                success_runs = calculator_sli_daily.success_runs + EXCLUDED.success_runs,
                sla_breaches = calculator_sli_daily.sla_breaches + EXCLUDED.sla_breaches,
                avg_duration_ms = (
                    (calculator_sli_daily.avg_duration_ms * calculator_sli_daily.total_runs + EXCLUDED.avg_duration_ms) 
                    / (calculator_sli_daily.total_runs + 1)
                ),
                avg_start_min_cet = (
                    (calculator_sli_daily.avg_start_min_cet * calculator_sli_daily.total_runs + EXCLUDED.avg_start_min_cet)
                    / (calculator_sli_daily.total_runs + 1)
                ),
                avg_end_min_cet = (
                    (calculator_sli_daily.avg_end_min_cet * calculator_sli_daily.total_runs + EXCLUDED.avg_end_min_cet)
                    / (calculator_sli_daily.total_runs + 1)
                ),
                computed_at = NOW()
            """;

        int successIncr = "SUCCESS".equals(status) ? 1 : 0;
        int breachIncr = slaBreached ? 1 : 0;

        try {
            jdbcTemplate.update(sql,
                    calculatorId, tenantId, reportingDate,
                    successIncr, breachIncr, durationMs, startMinCet, endMinCet
            );
        } catch (Exception e) {
            log.error("Failed to upsert daily aggregate for calculator {} on {}",
                    calculatorId, reportingDate, e);
            throw new RuntimeException("Failed to update daily aggregate", e);
        }
    }

    /**
     * Fetch recent aggregates for trending
     */
    public List<DailyAggregate> findRecentAggregates(
            String calculatorId, String tenantId, int days) {

        String sql = """
            SELECT calculator_id, tenant_id, day_cet, total_runs, success_runs,
                   sla_breaches, avg_duration_ms, avg_start_min_cet, avg_end_min_cet, computed_at
            FROM calculator_sli_daily
            WHERE calculator_id = ? AND tenant_id = ?
            AND day_cet >= CURRENT_DATE - INTERVAL '? days'
            ORDER BY day_cet DESC
            """;

        try {
            return jdbcTemplate.query(sql, new DailyAggregateRowMapper(),
                    calculatorId, tenantId, days);
        } catch (Exception e) {
            log.error("Failed to fetch recent aggregates for calculator {}", calculatorId, e);
            throw new RuntimeException("Failed to fetch daily aggregates", e);
        }
    }

    /**
     * Get aggregates for specific reporting dates (for MONTHLY calculators)
     */
    public List<DailyAggregate> findByReportingDates(
            String calculatorId, String tenantId, List<LocalDate> reportingDates) {

        if (reportingDates == null || reportingDates.isEmpty()) {
            return Collections.emptyList();
        }

        String placeholders = String.join(",", Collections.nCopies(reportingDates.size(), "?"));

        String sql = String.format("""
            SELECT calculator_id, tenant_id, day_cet, total_runs, success_runs,
                   sla_breaches, avg_duration_ms, avg_start_min_cet, avg_end_min_cet, computed_at
            FROM calculator_sli_daily
            WHERE calculator_id = ? AND tenant_id = ?
            AND day_cet IN (%s)
            ORDER BY day_cet DESC
            """, placeholders);

        Object[] params = new Object[2 + reportingDates.size()];
        params[0] = calculatorId;
        params[1] = tenantId;
        for (int i = 0; i < reportingDates.size(); i++) {
            params[2 + i] = reportingDates.get(i);
        }

        try {
            return jdbcTemplate.query(sql, new DailyAggregateRowMapper(), params);
        } catch (Exception e) {
            log.error("Failed to fetch aggregates by reporting dates", e);
            throw new RuntimeException("Failed to fetch aggregates by date", e);
        }
    }

    private static class DailyAggregateRowMapper implements RowMapper<DailyAggregate> {
        @Override
        public DailyAggregate mapRow(ResultSet rs, int rowNum) {
            try {
                return DailyAggregate.builder()
                        .calculatorId(rs.getString("calculator_id"))
                        .tenantId(rs.getString("tenant_id"))
                        .dayCet(rs.getDate("day_cet").toLocalDate())
                        .totalRuns(rs.getInt("total_runs"))
                        .successRuns(rs.getInt("success_runs"))
                        .slaBreaches(rs.getInt("sla_breaches"))
                        .avgDurationMs(rs.getLong("avg_duration_ms"))
                        .avgStartMinCet(rs.getInt("avg_start_min_cet"))
                        .avgEndMinCet(rs.getInt("avg_end_min_cet"))
                        .computedAt(rs.getTimestamp("computed_at").toInstant())
                        .build();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map daily aggregate", e);
            }
        }
    }
}
```

## File: src/main/java/com/company/observability/repository/SlaBreachEventRepository.java
```java
package com.company.observability.repository;

import com.company.observability.domain.SlaBreachEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SlaBreachEventRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * FIXED: Idempotent save - throws exception if duplicate
     * Caller must handle DuplicateKeyException
     */
    public SlaBreachEvent save(SlaBreachEvent breach) throws DuplicateKeyException {
        if (breach.getCreatedAt() == null) {
            breach.setCreatedAt(Instant.now());
        }

        String sql = """
            INSERT INTO sla_breach_events (
                run_id, calculator_id, calculator_name, tenant_id,
                breach_type, expected_value, actual_value, severity,
                alerted, alerted_at, alert_status, retry_count, last_error, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.setInt(12, breach.getRetryCount() != null ? breach.getRetryCount() : 0);
            ps.setString(13, breach.getLastError());
            ps.setTimestamp(14, Timestamp.from(breach.getCreatedAt()));
            return ps;
        }, keyHolder);

        breach.setBreachId(keyHolder.getKey().longValue());
        return breach;
    }

    /**
     * Find unalerted breaches for batch processing
     */
    public List<SlaBreachEvent> findUnalertedBreaches(int limit) {
        String sql = """
            SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                   breach_type, expected_value, actual_value, severity,
                   alerted, alerted_at, alert_status, retry_count, last_error, created_at
            FROM sla_breach_events
            WHERE alerted = false
            AND alert_status IN ('PENDING', 'FAILED')
            ORDER BY created_at ASC
            LIMIT ?
            """;

        return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(), limit);
    }

    /**
     * Update breach status after alert attempt
     */
    public void update(SlaBreachEvent breach) {
        String sql = """
            UPDATE sla_breach_events
            SET alerted = ?,
                alerted_at = ?,
                alert_status = ?,
                retry_count = ?,
                last_error = ?
            WHERE breach_id = ?
            """;

        jdbcTemplate.update(sql,
                breach.getAlerted(),
                breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null,
                breach.getAlertStatus(),
                breach.getRetryCount(),
                breach.getLastError(),
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
                    .retryCount(rs.getInt("retry_count"))
                    .lastError(rs.getString("last_error"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .build();
        }
    }
}
```

## File: src/main/java/com/company/observability/scheduled/LiveSlaBreachDetectionJob.java
```java
package com.company.observability.scheduled;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.SlaEvaluationResult;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * LIVE SLA BREACH DETECTION (every 15 seconds)
 * Checks Redis sorted set for runs past SLA deadline
 * Much faster than database polling, near real-time detection
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.sla.live-detection.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LiveSlaBreachDetectionJob {

    private final SlaMonitoringCache slaMonitoringCache;
    private final CalculatorRunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${observability.sla.live-detection.interval-ms:15000}")
    private long detectionIntervalMs;

    /**
     * Check for SLA breaches every 15 seconds (near real-time)
     * Uses Redis sorted set for fast lookups
     */
    @Scheduled(
            fixedDelayString = "${observability.sla.live-detection.interval-ms:15000}",
            initialDelayString = "${observability.sla.live-detection.initial-delay-ms:10000}"
    )
    @Transactional
    public void detectLiveSlaBreaches() {
        Instant startTime = Instant.now();

        try {
            // Get runs that exceeded SLA from Redis (very fast)
            List<Map<String, Object>> breachedRuns = slaMonitoringCache.getBreachedRuns();

            if (breachedRuns.isEmpty()) {
                log.debug("No live SLA breaches detected");
                recordMetrics(0, Duration.between(startTime, Instant.now()));
                return;
            }

            log.warn("LIVE DETECTION: Found {} runs past SLA deadline", breachedRuns.size());

            int processedCount = 0;

            for (Map<String, Object> runInfo : breachedRuns) {
                String runId = (String) runInfo.get("runId");

                try {
                    // Verify run is still RUNNING in database
                    Optional<CalculatorRun> runOpt = runRepository.findById(runId);

                    if (runOpt.isEmpty()) {
                        log.warn("Run {} not found in database, deregistering", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId);
                        continue;
                    }

                    CalculatorRun run = runOpt.get();

                    // Check if already marked as breached or completed
                    if (!"RUNNING".equals(run.getStatus())) {
                        log.debug("Run {} already completed, deregistering", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId);
                        continue;
                    }

                    if (Boolean.TRUE.equals(run.getSlaBreached())) {
                        log.debug("Run {} already marked as breached", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId);
                        continue;
                    }

                    // Mark as breached in database
                    String breachReason = buildBreachReason(run);
                    int updated = runRepository.markSlaBreached(runId, breachReason);

                    if (updated > 0) {
                        // Publish breach event
                        String severity = determineSeverity(run);
                        SlaEvaluationResult result = new SlaEvaluationResult(
                                true,
                                breachReason,
                                severity
                        );

                        run.setSlaBreached(true);
                        run.setSlaBreachReason(breachReason);

                        eventPublisher.publishEvent(new SlaBreachedEvent(run, result));

                        // Deregister (no longer need to monitor)
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId);

                        processedCount++;

                        log.warn("LIVE BREACH: Run {} marked as breached ({})", runId, breachReason);

                        meterRegistry.counter("sla.breaches.live_detected",
                                "calculator", run.getCalculatorId(),
                                "severity", severity
                        ).increment();
                    }

                } catch (Exception e) {
                    log.error("Failed to process live SLA breach for run {}", runId, e);
                }
            }

            Duration executionTime = Duration.between(startTime, Instant.now());
            log.info("Live SLA detection completed: {}/{} breaches processed in {}ms",
                    processedCount, breachedRuns.size(), executionTime.toMillis());

            recordMetrics(processedCount, executionTime);

        } catch (Exception e) {
            log.error("Live SLA breach detection job failed", e);
            meterRegistry.counter("sla.breach.live_detection.failures").increment();
        }
    }

    /**
     * Also check for runs approaching SLA (early warning)
     */
    @Scheduled(
            fixedDelayString = "${observability.sla.early-warning.interval-ms:60000}",
            initialDelayString = "30000"
    )
    public void detectApproachingSla() {
        try {
            // Get runs that will breach SLA in next 10 minutes
            List<Map<String, Object>> approachingRuns =
                    slaMonitoringCache.getApproachingSlaRuns(10);

            if (!approachingRuns.isEmpty()) {
                log.info("EARLY WARNING: {} runs approaching SLA deadline (within 10 min)",
                        approachingRuns.size());

                for (Map<String, Object> runInfo : approachingRuns) {
                    log.warn("Run {} approaching SLA: calculator={}, deadline in ~{} min",
                            runInfo.get("runId"),
                            runInfo.get("calculatorName"),
                            calculateMinutesUntilSla(runInfo));
                }

                meterRegistry.gauge("sla.approaching.count", approachingRuns.size());
            }

        } catch (Exception e) {
            log.error("Failed to detect approaching SLA runs", e);
        }
    }

    private String buildBreachReason(CalculatorRun run) {
        long delayMinutes = Duration.between(run.getSlaTime(), Instant.now()).toMinutes();
        return String.format(
                "Still running %d minutes past SLA deadline (detected live via Redis monitoring)",
                delayMinutes
        );
    }

    private String determineSeverity(CalculatorRun run) {
        long delayMinutes = Duration.between(run.getSlaTime(), Instant.now()).toMinutes();

        if (delayMinutes > 120) return "CRITICAL";
        if (delayMinutes > 60) return "HIGH";
        if (delayMinutes > 30) return "MEDIUM";
        return "LOW";
    }

    private long calculateMinutesUntilSla(Map<String, Object> runInfo) {
        long slaTime = ((Number) runInfo.get("slaTime")).longValue();
        long now = Instant.now().toEpochMilli();
        return (slaTime - now) / 60000;
    }

    private void recordMetrics(int breachedCount, Duration executionTime) {
        meterRegistry.counter("sla.breach.live_detection.runs").increment();
        meterRegistry.timer("sla.breach.live_detection.duration").record(executionTime);

        if (breachedCount > 0) {
            meterRegistry.gauge("sla.breach.live_detection.last_breaches", breachedCount);
        }

        // Record monitored run count
        long monitoredCount = slaMonitoringCache.getMonitoredRunCount();
        meterRegistry.gauge("sla.monitoring.active_runs", monitoredCount);
    }
}
```

## File: src/main/java/com/company/observability/scheduled/PartitionManagementJob.java
```java
package com.company.observability.scheduled;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Async partition management with monitoring
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.partitions.management.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PartitionManagementJob {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * Create partitions daily at 1 AM
     * Creates partitions for next 60 days
     */
    @Scheduled(cron = "${observability.partitions.management.create-cron:0 0 1 * * *}")
    public void createPartitions() {
        log.info("Starting partition creation job (async)");

        CompletableFuture.runAsync(() -> {
            try {
                jdbcTemplate.execute("SELECT create_calculator_run_partitions()");

                log.info("Successfully created calculator_runs partitions for next 60 days");
                meterRegistry.counter("partitions.create.success").increment();

                // Log partition statistics
                logPartitionStatistics();

            } catch (Exception e) {
                log.error("Failed to create partitions", e);
                meterRegistry.counter("partitions.create.failures").increment();
            }
        });
    }

    /**
     * Drop old partitions weekly on Sunday at 2 AM
     * Drops partitions older than 395 days (13+ months)
     */
    @Scheduled(cron = "${observability.partitions.management.drop-cron:0 0 2 * * SUN}")
    public void dropOldPartitions() {
        log.info("Starting old partition cleanup job (async)");

        CompletableFuture.runAsync(() -> {
            try {
                jdbcTemplate.execute("SELECT drop_old_calculator_run_partitions()");

                log.info("Successfully dropped old calculator_runs partitions");
                meterRegistry.counter("partitions.drop.success").increment();

                // Log remaining partition count
                logPartitionStatistics();

            } catch (Exception e) {
                log.error("Failed to drop old partitions", e);
                meterRegistry.counter("partitions.drop.failures").increment();
            }
        });
    }

    /**
     * Monitor partition health daily at 6 AM
     */
    @Scheduled(cron = "${observability.partitions.monitoring.cron:0 0 6 * * *}")
    public void monitorPartitionHealth() {
        log.info("Running partition health monitoring");

        try {
            List<Map<String, Object>> stats = jdbcTemplate.queryForList(
                    "SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 30"
            );

            long totalRows = 0;
            long dailyRows = 0;
            long monthlyRows = 0;

            for (Map<String, Object> stat : stats) {
                Long rowCount = ((Number) stat.get("row_count")).longValue();
                Long daily = ((Number) stat.get("daily_runs")).longValue();
                Long monthly = ((Number) stat.get("monthly_runs")).longValue();

                totalRows += rowCount;
                dailyRows += daily;
                monthlyRows += monthly;

                log.debug("Partition {}: {} rows ({} DAILY, {} MONTHLY), size: {}",
                        stat.get("partition_name"),
                        rowCount,
                        daily,
                        monthly,
                        stat.get("total_size"));
            }

            // Record metrics
            meterRegistry.gauge("partitions.total_rows", totalRows);
            meterRegistry.gauge("partitions.daily_rows", dailyRows);
            meterRegistry.gauge("partitions.monthly_rows", monthlyRows);
            meterRegistry.gauge("partitions.count", stats.size());

            log.info("Partition health: {} partitions, {} total rows ({} DAILY, {} MONTHLY)",
                    stats.size(), totalRows, dailyRows, monthlyRows);

        } catch (Exception e) {
            log.error("Failed to monitor partition health", e);
            meterRegistry.counter("partitions.monitoring.failures").increment();
        }
    }

    private void logPartitionStatistics() {
        try {
            List<Map<String, Object>> recentStats = jdbcTemplate.queryForList(
                    "SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 7"
            );

            log.info("Recent partition statistics:");
            for (Map<String, Object> stat : recentStats) {
                log.info("  {}: {} rows, {} size",
                        stat.get("partition_name"),
                        stat.get("row_count"),
                        stat.get("total_size"));
            }
        } catch (Exception e) {
            log.warn("Failed to log partition statistics", e);
        }
    }
}
```

## File: src/main/java/com/company/observability/security/RequestLoggingFilter.java
```java
package com.company.observability.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Add request ID to MDC for log correlation
 */
@Component
@Slf4j
public class RequestLoggingFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_REQUEST_ID_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Get or generate request ID
        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        // Add to MDC for logging
        MDC.put(MDC_REQUEST_ID_KEY, requestId);

        // Add to response header
        httpResponse.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID_KEY);
        }
    }
}
```

## File: src/main/java/com/company/observability/security/TenantContext.java
```java
package com.company.observability.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Extract tenant ID from authenticated user context
 * Prevents tenant spoofing via X-Tenant-Id header
 */
@Component
@Slf4j
public class TenantContext {

    /**
     * Get tenant ID from JWT token claim
     * Falls back to "default" if not present
     */
    public String getCurrentTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("No authenticated user, using default tenant");
            return "default";
        }

        if (authentication.getPrincipal() instanceof Jwt jwt) {
            // Extract tenant from JWT claim
            // Azure AD custom claim: "extension_TenantId" or "tenant_id"
            String tenantId = jwt.getClaimAsString("tenant_id");

            if (tenantId == null) {
                tenantId = jwt.getClaimAsString("extension_TenantId");
            }

            if (tenantId == null) {
                log.warn("No tenant_id claim in JWT, using default tenant");
                return "default";
            }

            return tenantId;
        }

        log.warn("Unexpected authentication principal type: {}",
                authentication.getPrincipal().getClass());
        return "default";
    }

    /**
     * Get user ID from JWT token
     */
    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("sub"); // Subject claim
        }

        return "anonymous";
    }

    /**
     * Get user email from JWT token
     */
    public String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String email = jwt.getClaimAsString("email");
            if (email == null) {
                email = jwt.getClaimAsString("preferred_username");
            }
            return email;
        }

        return null;
    }
}
```

## File: src/main/java/com/company/observability/service/AlertHandlerService.java
```java
package com.company.observability.service;

import com.company.observability.domain.*;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.util.SlaEvaluationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * FIXED: Idempotent alert handling with circuit breaker
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertHandlerService {

    private final SlaBreachEventRepository breachRepository;
    private final AzureMonitorAlertSender azureAlertSender;
    private final MeterRegistry meterRegistry;

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
                .expectedValue(calculateExpectedValue(run))
                .actualValue(calculateActualValue(run))
                .severity(result.getSeverity())
                .alerted(false)
                .alertStatus("PENDING")
                .retryCount(0)
                .createdAt(Instant.now())
                .build();

        SlaBreachEvent savedBreach;

        try {
            // FIXED: Save with idempotency - will throw exception if duplicate
            savedBreach = breachRepository.save(breach);

            meterRegistry.counter("sla.breaches.created",
                    "calculator", run.getCalculatorId(),
                    "severity", result.getSeverity()
            ).increment();

        } catch (DuplicateKeyException e) {
            log.warn("SLA breach already recorded for run {}, skipping duplicate alert",
                    run.getRunId());

            meterRegistry.counter("sla.breaches.duplicate",
                    "calculator", run.getCalculatorId()
            ).increment();

            return; // Idempotent - exit gracefully
        }

        // Send alert with retry and circuit breaker
        sendAlertWithRetry(savedBreach, run);
    }

    @Retry(name = "azureMonitorAlert", fallbackMethod = "alertSendFallback")
    @CircuitBreaker(name = "azureMonitorAlert", fallbackMethod = "alertSendFallback")
    private void sendAlertWithRetry(SlaBreachEvent breach, CalculatorRun run) {
        try {
            azureAlertSender.sendAlert(breach, run);

            breach.setAlerted(true);
            breach.setAlertedAt(Instant.now());
            breach.setAlertStatus("SENT");
            breachRepository.update(breach);

            meterRegistry.counter("sla.alerts.sent",
                    "calculator", run.getCalculatorId(),
                    "severity", breach.getSeverity()
            ).increment();

            log.info("Alert sent successfully for breach {}", breach.getBreachId());

        } catch (Exception e) {
            log.error("Failed to send alert for breach {}", breach.getBreachId(), e);

            breach.setAlertStatus("FAILED");
            breach.setRetryCount(breach.getRetryCount() + 1);
            breach.setLastError(e.getMessage());
            breachRepository.update(breach);

            meterRegistry.counter("sla.alerts.failed",
                    "calculator", run.getCalculatorId()
            ).increment();

            throw e; // Re-throw to trigger retry/circuit breaker
        }
    }

    /**
     * Fallback when Azure Monitor is unavailable
     */
    private void alertSendFallback(SlaBreachEvent breach, CalculatorRun run, Exception e) {
        log.error("Azure Monitor unavailable for breach {}, marking for retry: {}",
                breach.getBreachId(), e.getMessage());

        breach.setAlertStatus("PENDING");
        breach.setRetryCount(breach.getRetryCount() + 1);
        breach.setLastError("Circuit open: " + e.getMessage());
        breachRepository.update(breach);

        meterRegistry.counter("sla.alerts.circuit_open",
                "calculator", run.getCalculatorId()
        ).increment();

        // Batch job will retry later
    }

    private String determineBreachType(String reason) {
        if (reason == null) return "UNKNOWN";
        if (reason.contains("Finished") && reason.contains("late")) return "TIME_EXCEEDED";
        if (reason.contains("Still running")) return "STILL_RUNNING_PAST_SLA";
        if (reason.contains("Duration") && reason.contains("exceeded")) return "DURATION_EXCEEDED";
        if (reason.contains("FAILED")) return "FAILED";
        if (reason.contains("TIMEOUT")) return "TIMEOUT";
        return "UNKNOWN";
    }

    private Long calculateExpectedValue(CalculatorRun run) {
        if (run.getSlaTime() != null) {
            return run.getSlaTime().getEpochSecond();
        } else if (run.getExpectedDurationMs() != null) {
            return run.getExpectedDurationMs();
        }
        return null;
    }

    private Long calculateActualValue(CalculatorRun run) {
        if (run.getEndTime() != null && run.getSlaTime() != null) {
            return run.getEndTime().getEpochSecond();
        } else if (run.getDurationMs() != null) {
            return run.getDurationMs();
        }
        return null;
    }
}
```

## File: src/main/java/com/company/observability/service/AzureMonitorAlertSender.java
```java
// File: src/main/java/com/company/observability/service/AzureMonitorAlertSender.java
package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.exception.AlertSendException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

## File: src/main/java/com/company/observability/service/CacheEvictionService.java
```java
package com.company.observability.service;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CacheEvictionService {

    private final RedisCalculatorCache redisCache;
    private final MeterRegistry meterRegistry;

    @EventListener
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        CalculatorRun run = event.getRun();

        // Evict full response cache only
        // Sorted set already updated by write-through in repository
        redisCache.evictStatusResponse(run.getCalculatorId(), run.getTenantId());

        meterRegistry.counter("cache.evictions",
                "calculator", run.getCalculatorId(),
                "event", "completed"
        ).increment();

        log.debug("Evicted status response for {}", run.getCalculatorId());
    }

    @EventListener
    @Async
    public void onRunStarted(RunStartedEvent event) {
        CalculatorRun run = event.getRun();

        // Evict full response cache
        redisCache.evictStatusResponse(run.getCalculatorId(), run.getTenantId());

        meterRegistry.counter("cache.evictions",
                "calculator", run.getCalculatorId(),
                "event", "started"
        ).increment();
    }

    @EventListener
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        CalculatorRun run = event.getRun();

        // Evict full response cache
        redisCache.evictStatusResponse(run.getCalculatorId(), run.getTenantId());

        meterRegistry.counter("cache.evictions",
                "calculator", run.getCalculatorId(),
                "event", "sla_breached"
        ).increment();
    }
}
```

## File: src/main/java/com/company/observability/service/CacheWarmingService.java
```java
package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.response.RunSummaryResponse;
import com.company.observability.event.*;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GT Enhancement: Automatically warm Redis cache when calculator runs complete
 * This ensures the next UI poll gets immediate cache hit instead of database query
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

    /**
     * When run starts, invalidate cache so status shows RUNNING immediately
     */
    @EventListener
    @Async
    public void onRunStarted(RunStartedEvent event) {
        invalidateCacheForRun(event.getRun());
    }

    /**
     * When run completes, immediately warm the cache with fresh data
     * This prevents the next UI query from hitting the database
     */
    @EventListener
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        warmCacheForRun(event.getRun());
    }

    /**
     * When SLA breaches, update cache with breach information
     */
    @EventListener
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        warmCacheForRun(event.getRun());
    }

    /**
     * Invalidate cache entries for a calculator
     */
    private void invalidateCacheForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        String frequency = run.getFrequency();

        log.debug("Invalidating cache for calculator {} (started)", calculatorId);

        String cacheName = "recentRuns:" + frequency;
        Cache cache = cacheManager.getCache(cacheName);

        if (cache != null) {
            // Evict common limit values
            for (int limit : List.of(5, 10, 20, 50, 100)) {
                String key = calculatorId + "-" + tenantId + "-" + limit;
                cache.evict(key);
            }
        }
    }

    /**
     * Proactively warm cache with fresh data from database
     * This is faster than waiting for next query
     */
    private void warmCacheForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        String frequency = run.getFrequency();

        log.info("Warming cache for calculator {} (frequency: {})", calculatorId, frequency);

        try {
            // Warm cache for common limit values (most UI queries use these)
            warmRecentRunsCache(calculatorId, tenantId, frequency, 5);
            warmRecentRunsCache(calculatorId, tenantId, frequency, 10);
            warmRecentRunsCache(calculatorId, tenantId, frequency, 15);
            warmRecentRunsCache(calculatorId, tenantId, frequency, 20);

            log.info("Successfully warmed cache for calculator {}", calculatorId);

        } catch (Exception e) {
            log.error("Failed to warm cache for calculator {}", calculatorId, e);
        }
    }

    /**
     * Fetch fresh data and populate Redis cache
     */
    private void warmRecentRunsCache(
            String calculatorId, String tenantId, String frequency, int limit) {

        String cacheName = "recentRuns:" + frequency;
        String cacheKey = calculatorId + "-" + tenantId + "-" + limit;

        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.warn("Cache not found: {}", cacheName);
            return;
        }

        // Fetch fresh data from database
        List<CalculatorRun> runs = runRepository.findRecentRuns(
                calculatorId, tenantId, limit);

        // Convert to DTOs
        List<RunSummaryResponse> responses = runs.stream()
                .map(this::toRunSummaryResponse)
                .collect(Collectors.toList());

        // Put in Redis cache
        cache.put(cacheKey, responses);

        log.debug("Warmed cache: {} -> {} ({} runs)",
                cacheName, cacheKey, responses.size());
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

## File: src/main/java/com/company/observability/service/RunIngestionService.java
```java
package com.company.observability.service;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.request.*;
import com.company.observability.event.*;
import com.company.observability.repository.*;
import com.company.observability.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunIngestionService {

    private final CalculatorRunRepository runRepository;
    private final DailyAggregateRepository dailyAggregateRepository;
    private final SlaEvaluationService slaEvaluationService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final RedisCalculatorCache redisCache;
    private final SlaMonitoringCache slaMonitoringCache;

    @Transactional
    public CalculatorRun startRun(StartRunRequest request, String tenantId) {
        // Check for existing run using partition key
        Optional<CalculatorRun> existing = runRepository.findById(
                request.getRunId(), request.getReportingDate());

        if (existing.isPresent()) {
            log.info("Duplicate start request for run {}", request.getRunId());
            meterRegistry.counter("calculator.runs.start.duplicate").increment();
            return existing.get();
        }

        log.info("Starting new run {} for calculator {} (reporting_date: {})",
                request.getRunId(), request.getCalculatorId(), request.getReportingDate());

        // Validate reporting_date matches frequency expectations
        validateReportingDate(request);

        Instant slaDeadline = TimeUtils.calculateSlaDeadline(
                request.getStartTime(), request.getSlaTimeCet());

        Instant estimatedEndTime = null;
        if (request.getExpectedDurationMs() != null) {
            estimatedEndTime = TimeUtils.calculateEstimatedEndTime(
                    request.getStartTime(), request.getExpectedDurationMs());
        }

        CalculatorRun run = CalculatorRun.builder()
                .runId(request.getRunId())
                .calculatorId(request.getCalculatorId())
                .calculatorName(request.getCalculatorName())
                .tenantId(tenantId)
                .frequency(request.getFrequency())
                .reportingDate(request.getReportingDate())
                .startTime(request.getStartTime())
                .startHourCet(TimeUtils.calculateCetHour(request.getStartTime()))
                .status("RUNNING")
                .slaTime(slaDeadline)
                .expectedDurationMs(request.getExpectedDurationMs())
                .estimatedStartTime(request.getStartTime())
                .estimatedEndTime(estimatedEndTime)
                .runParameters(request.getRunParameters())
                .slaBreached(false)
                .build();

        run = runRepository.upsert(run);

        // Register for live SLA monitoring
        slaMonitoringCache.registerForSlaMonitoring(run);

        eventPublisher.publishEvent(new RunStartedEvent(run));

        meterRegistry.counter("calculator.runs.started",
                "calculator", run.getCalculatorId(),
                "frequency", run.getFrequency()
        ).increment();

        log.info("Run {} started (reporting_date: {}, SLA deadline: {})",
                run.getRunId(), run.getReportingDate(), slaDeadline);

        return run;
    }

    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        // Try to find with recent reporting dates first
        Optional<CalculatorRun> runOpt = findRecentRun(runId);

        if (runOpt.isEmpty()) {
            throw new RuntimeException("Run not found: " + runId);
        }

        CalculatorRun run = runOpt.get();

        if (!run.getTenantId().equals(tenantId)) {
            throw new RuntimeException("Access denied to run " + runId + " for tenant " + tenantId);
        }

        if (!"RUNNING".equals(run.getStatus())) {
            log.info("Run {} already completed", runId);
            meterRegistry.counter("calculator.runs.complete.duplicate").increment();
            return run;
        }

        long durationMs = Duration.between(run.getStartTime(), request.getEndTime()).toMillis();

        run.setEndTime(request.getEndTime());
        run.setDurationMs(durationMs);
        run.setEndHourCet(TimeUtils.calculateCetHour(request.getEndTime()));
        run.setStatus(request.getStatus() != null ? request.getStatus() : "SUCCESS");

        SlaEvaluationResult slaResult = slaEvaluationService.evaluateSla(run);
        run.setSlaBreached(slaResult.isBreached());
        run.setSlaBreachReason(slaResult.getReason());

        run = runRepository.upsert(run);

        // Deregister from SLA monitoring
        slaMonitoringCache.deregisterFromSlaMonitoring(runId);

        updateDailyAggregate(run);

        meterRegistry.counter("calculator.runs.completed",
                "calculator", run.getCalculatorId(),
                "frequency", run.getFrequency(),
                "status", run.getStatus(),
                "sla_breached", String.valueOf(run.getSlaBreached())
        ).increment();

        if (slaResult.isBreached()) {
            eventPublisher.publishEvent(new SlaBreachedEvent(run, slaResult));
        } else {
            eventPublisher.publishEvent(new RunCompletedEvent(run));
        }

        log.info("Run {} completed (reporting_date: {})", runId, run.getReportingDate());

        return run;
    }

    /**
     * Find recent run by ID (checks recent partitions)
     */
    private Optional<CalculatorRun> findRecentRun(String runId) {
        // Try last 7 days of partitions
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            LocalDate reportingDate = today.minusDays(i);
            Optional<CalculatorRun> run = runRepository.findById(runId, reportingDate);
            if (run.isPresent()) {
                return run;
            }
        }

        // Fallback to full scan (slower)
        return runRepository.findById(runId);
    }

    /**
     * Validate reporting_date matches frequency expectations
     */
    private void validateReportingDate(StartRunRequest request) {
        if ("MONTHLY".equalsIgnoreCase(request.getFrequency())) {
            // MONTHLY runs should have end-of-month reporting date
            LocalDate reportingDate = request.getReportingDate();
            LocalDate nextDay = reportingDate.plusDays(1);

            if (nextDay.getMonth() == reportingDate.getMonth()) {
                log.warn("MONTHLY run {} has non-end-of-month reporting_date: {}",
                        request.getRunId(), reportingDate);
                // Don't fail - just warn
            }
        }
    }

    private void updateDailyAggregate(CalculatorRun run) {
        try {
            dailyAggregateRepository.upsertDaily(
                    run.getCalculatorId(),
                    run.getTenantId(),
                    run.getReportingDate(),
                    run.getStatus(),
                    run.getSlaBreached(),
                    run.getDurationMs(),
                    TimeUtils.calculateCetMinute(run.getStartTime()),
                    TimeUtils.calculateCetMinute(run.getEndTime())
            );
        } catch (Exception e) {
            log.error("Failed to update daily aggregate", e);
        }
    }
}
```

## File: src/main/java/com/company/observability/service/RunQueryService.java
```java
package com.company.observability.service;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.response.*;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.TimeUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunQueryService {

    private final CalculatorRunRepository runRepository;
    private final RedisCalculatorCache redisCache;
    private final MeterRegistry meterRegistry;

    /**
     * Get calculator status with partition-aware query
     */
    public CalculatorStatusResponse getCalculatorStatus(
            String calculatorId, String tenantId, String frequency, int historyLimit) {

        // Check full response cache first
        Optional<CalculatorStatusResponse> cachedResponse =
                redisCache.getStatusResponse(calculatorId, tenantId);

        if (cachedResponse.isPresent()) {
            meterRegistry.counter("query.calculator_status.cache_hit",
                    "tier", "response_cache"
            ).increment();
            return cachedResponse.get();
        }

        // Query with partition pruning based on frequency
        List<CalculatorRun> runs = runRepository.findRecentRuns(
                calculatorId, tenantId, frequency, historyLimit + 1);

        if (runs.isEmpty()) {
            throw new RuntimeException("Calculator not found: " + calculatorId);
        }

        // Build response
        CalculatorRun currentRun = runs.get(0);
        String calculatorName = currentRun.getCalculatorName();
        RunStatusInfo current = mapToRunStatusInfo(currentRun);

        List<RunStatusInfo> history = runs.stream()
                .skip(1)
                .map(this::mapToRunStatusInfo)
                .collect(Collectors.toList());

        CalculatorStatusResponse response = CalculatorStatusResponse.builder()
                .calculatorName(calculatorName)
                .lastRefreshed(Instant.now())
                .current(current)
                .history(history)
                .build();

        // Cache the response
        redisCache.cacheStatusResponse(calculatorId, tenantId, response);

        meterRegistry.counter("query.calculator_status.cache_miss",
                "frequency", frequency
        ).increment();

        return response;
    }

    /**
     * Batch query with partition pruning
     */
    public List<CalculatorStatusResponse> getBatchCalculatorStatus(
            List<String> calculatorIds, String tenantId, String frequency, int historyLimit) {

        long startTime = System.currentTimeMillis();

        // Batch check response cache
        Map<String, CalculatorStatusResponse> cachedResponses =
                redisCache.getBatchStatusResponses(calculatorIds, tenantId);

        List<String> cacheMisses = calculatorIds.stream()
                .filter(id -> !cachedResponses.containsKey(id))
                .collect(Collectors.toList());

        log.debug("BATCH ({}): {} cache hits, {} misses",
                frequency, cachedResponses.size(), cacheMisses.size());

        // Query missing calculators with partition pruning
        Map<String, CalculatorStatusResponse> freshResponses = new HashMap<>();

        if (!cacheMisses.isEmpty()) {
            Map<String, List<CalculatorRun>> runsByCalculator =
                    runRepository.findBatchRecentRuns(cacheMisses, tenantId, frequency, historyLimit + 1);

            // Build responses for cache misses
            for (String calcId : cacheMisses) {
                List<CalculatorRun> runs = runsByCalculator.get(calcId);

                if (runs == null || runs.isEmpty()) {
                    log.warn("No {} runs found for calculator {}", frequency, calcId);
                    continue;
                }

                CalculatorRun currentRun = runs.get(0);
                RunStatusInfo current = mapToRunStatusInfo(currentRun);

                List<RunStatusInfo> history = runs.stream()
                        .skip(1)
                        .map(this::mapToRunStatusInfo)
                        .collect(Collectors.toList());

                CalculatorStatusResponse response = CalculatorStatusResponse.builder()
                        .calculatorName(currentRun.getCalculatorName())
                        .lastRefreshed(Instant.now())
                        .current(current)
                        .history(history)
                        .build();

                freshResponses.put(calcId, response);
            }

            // Cache fresh responses
            redisCache.cacheBatchStatusResponses(freshResponses, tenantId);
        }

        // Combine cached + fresh responses
        List<CalculatorStatusResponse> result = calculatorIds.stream()
                .map(calcId -> {
                    CalculatorStatusResponse response = cachedResponses.get(calcId);
                    if (response == null) {
                        response = freshResponses.get(calcId);
                    }
                    return response;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        long duration = System.currentTimeMillis() - startTime;

        meterRegistry.counter("query.batch_status.requests",
                "count", String.valueOf(calculatorIds.size()),
                "frequency", frequency
        ).increment();

        meterRegistry.timer("query.batch_status.duration")
                .record(java.time.Duration.ofMillis(duration));

        log.debug("Batch query ({}) completed in {}ms: {}/{} calculators",
                frequency, duration, result.size(), calculatorIds.size());

        return result;
    }

    private RunStatusInfo mapToRunStatusInfo(CalculatorRun run) {
        return RunStatusInfo.builder()
                .runId(run.getRunId())
                .status(run.getStatus())
                .start(run.getStartTime())
                .end(run.getEndTime())
                .estimatedStart(run.getEstimatedStartTime())
                .estimatedEnd(run.getEstimatedEndTime())
                .sla(run.getSlaTime())
                .durationMs(run.getDurationMs())
                .durationFormatted(TimeUtils.formatDuration(run.getDurationMs()))
                .slaBreached(run.getSlaBreached())
                .slaBreachReason(run.getSlaBreachReason())
                .build();
    }
}
```

## File: src/main/java/com/company/observability/service/SlaEvaluationService.java
```java
package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.util.SlaEvaluationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class SlaEvaluationService {

    /**
     * UPDATED: Evaluate SLA based on absolute time deadline (CET)
     */
    public SlaEvaluationResult evaluateSla(CalculatorRun run) {
        List<String> breachReasons = new ArrayList<>();

        // Check 1: End time exceeded SLA deadline (absolute time in CET)
        if (run.getSlaTime() != null && run.getEndTime() != null) {
            if (run.getEndTime().isAfter(run.getSlaTime())) {
                long delaySeconds = java.time.Duration.between(
                        run.getSlaTime(),
                        run.getEndTime()
                ).getSeconds();

                breachReasons.add(String.format(
                        "Finished %d minutes late (SLA: %s, Actual: %s)",
                        delaySeconds / 60,
                        run.getSlaTime(),
                        run.getEndTime()
                ));
            }
        }

        // Check 2: Still running past SLA deadline
        if (run.getSlaTime() != null && "RUNNING".equals(run.getStatus())) {
            Instant now = Instant.now();
            if (now.isAfter(run.getSlaTime())) {
                long delaySeconds = java.time.Duration.between(
                        run.getSlaTime(),
                        now
                ).getSeconds();

                breachReasons.add(String.format(
                        "Still running %d minutes past SLA deadline",
                        delaySeconds / 60
                ));
            }
        }

        // Check 3: Duration exceeded expected duration (separate from SLA)
        if (run.getExpectedDurationMs() != null && run.getDurationMs() != null) {
            if (run.getDurationMs() > run.getExpectedDurationMs() * 1.5) { // 50% over
                breachReasons.add(String.format(
                        "Duration significantly exceeded: %dms vs expected %dms",
                        run.getDurationMs(),
                        run.getExpectedDurationMs()
                ));
            }
        }

        // Check 4: Run failed
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

        // Check how late the run finished
        if (run.getSlaTime() != null && run.getEndTime() != null) {
            long delayMinutes = java.time.Duration.between(
                    run.getSlaTime(),
                    run.getEndTime()
            ).toMinutes();

            if (delayMinutes > 60) return "CRITICAL";  // More than 1 hour late
            if (delayMinutes > 30) return "HIGH";      // 30-60 minutes late
            if (delayMinutes > 15) return "MEDIUM";    // 15-30 minutes late
            return "LOW";                               // Less than 15 minutes late
        }

        return "MEDIUM";
    }
}
```

## File: src/main/java/com/company/observability/util/SlaEvaluationResult.java
```java
package com.company.observability.util;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SlaEvaluationResult {
    private boolean breached;
    private String reason;
    private String severity;
}
```

## File: src/main/java/com/company/observability/util/TimeUtils.java
```java
package com.company.observability.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

public class TimeUtils {

    private static final ZoneId CET_ZONE = ZoneId.of("CET");

    /**
     * Calculate absolute SLA deadline time from start time and SLA time of day
     *
     * @param startTime When the run started (UTC)
     * @param slaTimeCet Target completion time in CET (e.g., 06:15:00)
     * @return Absolute deadline time in UTC
     */
    public static Instant calculateSlaDeadline(Instant startTime, LocalTime slaTimeCet) {
        if (startTime == null || slaTimeCet == null) return null;

        // Convert start time to CET date
        ZonedDateTime startCet = startTime.atZone(CET_ZONE);
        LocalDate startDate = startCet.toLocalDate();

        // Combine with SLA time
        ZonedDateTime slaDateTime = ZonedDateTime.of(startDate, slaTimeCet, CET_ZONE);

        // If SLA time is before start time, it means next day
        if (slaDateTime.isBefore(startCet)) {
            slaDateTime = slaDateTime.plusDays(1);
        }

        return slaDateTime.toInstant();
    }

    /**
     * Calculate estimated end time from start time and expected duration
     */
    public static Instant calculateEstimatedEndTime(Instant startTime, Long expectedDurationMs) {
        if (startTime == null || expectedDurationMs == null) return null;
        return startTime.plusMillis(expectedDurationMs);
    }

    /**
     * Calculate next expected start time for a calculator
     *
     * @param lastRunStart Last run start time
     * @param frequency DAILY or MONTHLY
     * @param estimatedStartTimeCet Estimated start time in CET (e.g., 04:15:00)
     * @return Next estimated start time
     */
    public static Instant calculateNextEstimatedStart(
            Instant lastRunStart, String frequency, LocalTime estimatedStartTimeCet) {

        if (lastRunStart == null || estimatedStartTimeCet == null) return null;

        ZonedDateTime lastStartCet = lastRunStart.atZone(CET_ZONE);
        LocalDate nextDate;

        if ("MONTHLY".equals(frequency)) {
            nextDate = lastStartCet.toLocalDate().plusMonths(1);
        } else {
            nextDate = lastStartCet.toLocalDate().plusDays(1);
        }

        ZonedDateTime nextStart = ZonedDateTime.of(nextDate, estimatedStartTimeCet, CET_ZONE);
        return nextStart.toInstant();
    }

    public static BigDecimal calculateCetHour(Instant instant) {
        if (instant == null) return null;

        ZonedDateTime cetTime = instant.atZone(CET_ZONE);
        double hour = cetTime.getHour() + (cetTime.getMinute() / 60.0);
        return BigDecimal.valueOf(hour).setScale(2, RoundingMode.HALF_UP);
    }

    public static Integer calculateCetMinute(Instant instant) {
        if (instant == null) return null;

        ZonedDateTime cetTime = instant.atZone(CET_ZONE);
        return cetTime.getHour() * 60 + cetTime.getMinute();
    }

    public static LocalDate getCetDate(Instant instant) {
        if (instant == null) return null;
        return instant.atZone(CET_ZONE).toLocalDate();
    }

    public static String formatDuration(Long durationMs) {
        if (durationMs == null) return null;

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
        if (hourCet == null) return null;

        int hour = hourCet.intValue();
        int minute = hourCet.subtract(BigDecimal.valueOf(hour))
                .multiply(BigDecimal.valueOf(60))
                .intValue();

        return String.format("%02d:%02d", hour, minute);
    }

    public static String formatCetMinute(Integer cetMinute) {
        if (cetMinute == null) return null;

        int hours = cetMinute / 60;
        int minutes = cetMinute % 60;

        return String.format("%02d:%02d", hours, minutes);
    }
}
```

## File: src/main/java/com/company/observability/ObservabilityServiceApplication.java
```java
package com.company.observability;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAsync
@OpenAPIDefinition(
        info = @Info(
                title = "Observability Service API",
                version = "1.0.0",
                description = "Calculator runtime observability and SLA monitoring service"
        )
)
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class ObservabilityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ObservabilityServiceApplication.class, args);
    }
}
```

## File: src/main/resources/db/migration/V1__extensions.sql
```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
```

## File: src/main/resources/db/migration/V10__helper_views.sql
```sql
-- ================================================================
-- HELPER VIEWS
-- ================================================================

-- View for recent DAILY runs (optimized for 2-3 day lookback)
CREATE OR REPLACE VIEW recent_daily_runs AS
SELECT *
FROM calculator_runs
WHERE frequency = 'DAILY'
AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'
AND reporting_date <= CURRENT_DATE;

-- View for recent MONTHLY runs (end of month only)
CREATE OR REPLACE VIEW recent_monthly_runs AS
SELECT *
FROM calculator_runs
WHERE frequency = 'MONTHLY'
AND reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE
AND reporting_date >= CURRENT_DATE - INTERVAL '13 months';

-- View for active/running calculators
CREATE OR REPLACE VIEW active_calculator_runs AS
SELECT *
FROM calculator_runs
WHERE status = 'RUNNING'
AND reporting_date >= CURRENT_DATE - INTERVAL '7 days';
```

## File: src/main/resources/db/migration/V2__calculator_runs_base_table.sql
```sql
CREATE TABLE IF NOT EXISTS calculator_runs (
    run_id VARCHAR(100) NOT NULL,

    -- Calculator metadata
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    frequency VARCHAR(20) NOT NULL CHECK (frequency IN ('DAILY', 'MONTHLY')),

    -- Key attribute for partitioning
    reporting_date DATE NOT NULL,

    -- Timing information
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    duration_ms BIGINT,

    -- CET time conversions for display
    start_hour_cet DECIMAL(4, 2),
    end_hour_cet DECIMAL(4, 2),

    -- Status
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED')),

    -- SLA tracking (absolute time-based)
    sla_time TIMESTAMPTZ,
    expected_duration_ms BIGINT,
    estimated_start_time TIMESTAMPTZ,
    estimated_end_time TIMESTAMPTZ,

    -- SLA breach tracking
    sla_breached BOOLEAN DEFAULT false,
    sla_breach_reason TEXT,

    -- JSON columns for flexibility
    run_parameters JSONB,  -- Airflow DAG run configuration, input parameters
    additional_attributes JSONB,  -- Generic extensibility for future needs

    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite primary key including partition key
    PRIMARY KEY (run_id, reporting_date)
) PARTITION BY RANGE (reporting_date);
```

## File: src/main/resources/db/migration/V3__calculator_runs_indexes.sql
```sql
-- flyway:transactional=false

CREATE INDEX IF NOT EXISTS calculator_runs_lookup_idx
    ON calculator_runs (calculator_id, tenant_id, reporting_date DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS calculator_runs_tenant_idx
    ON calculator_runs (tenant_id, reporting_date DESC);

CREATE INDEX IF NOT EXISTS calculator_runs_status_idx
    ON calculator_runs (status, reporting_date DESC)
    WHERE status = 'RUNNING';

CREATE INDEX IF NOT EXISTS calculator_runs_sla_idx
    ON calculator_runs (sla_time, status)
    WHERE status = 'RUNNING' AND sla_time IS NOT NULL;

CREATE INDEX IF NOT EXISTS calculator_runs_frequency_idx
    ON calculator_runs (frequency, reporting_date DESC);
```

## File: src/main/resources/db/migration/V4__calculator_runs_partitions.sql
```sql
-- Function to create daily partitions for next 60 days
CREATE OR REPLACE FUNCTION create_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    start_date DATE;
    end_date DATE;
    partition_date DATE;
    partition_name TEXT;
    partition_exists BOOLEAN;
BEGIN
    -- Create partitions from yesterday to 60 days in future
    start_date := CURRENT_DATE - INTERVAL '1 day';
    end_date := CURRENT_DATE + INTERVAL '60 days';

    partition_date := start_date;

    WHILE partition_date <= end_date LOOP
        partition_name := 'calculator_runs_' || TO_CHAR(partition_date, 'YYYY_MM_DD');

        -- Check if partition exists
        SELECT EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = partition_name
            AND n.nspname = 'public'
        ) INTO partition_exists;

        IF NOT partition_exists THEN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF calculator_runs
                FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                partition_date,
                partition_date + INTERVAL '1 day'
            );

            RAISE NOTICE 'Created partition: %', partition_name;
        END IF;

        partition_date := partition_date + INTERVAL '1 day';
    END LOOP;

    RAISE NOTICE 'Partition creation completed';
END;
$$ LANGUAGE plpgsql;

-- Function to drop old partitions (older than retention period)
CREATE OR REPLACE FUNCTION drop_old_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    partition_record RECORD;
    cutoff_date DATE;
    daily_retention_days INTEGER := 7;      -- Keep DAILY runs for 7 days
    monthly_retention_days INTEGER := 395;  -- Keep MONTHLY runs for ~13 months
BEGIN
    -- Drop partitions older than the longest retention period
    cutoff_date := CURRENT_DATE - INTERVAL '395 days';

    FOR partition_record IN
        SELECT
            c.relname AS partition_name,
            pg_get_expr(c.relpartbound, c.oid) AS partition_bound
        FROM pg_class c
        JOIN pg_inherits i ON c.oid = i.inhrelid
        JOIN pg_class p ON i.inhparent = p.oid
        WHERE p.relname = 'calculator_runs'
        AND c.relname LIKE 'calculator_runs_%'
    LOOP
        -- Extract date from partition name (format: calculator_runs_YYYY_MM_DD)
        DECLARE
            partition_date DATE;
            date_string TEXT;
        BEGIN
            date_string := SUBSTRING(partition_record.partition_name FROM 'calculator_runs_(.*)');
            partition_date := TO_DATE(REPLACE(date_string, '_', '-'), 'YYYY-MM-DD');

            IF partition_date < cutoff_date THEN
                EXECUTE format('DROP TABLE IF EXISTS %I', partition_record.partition_name);
                RAISE NOTICE 'Dropped old partition: %', partition_record.partition_name;
            END IF;
        EXCEPTION
            WHEN OTHERS THEN
                RAISE WARNING 'Failed to process partition %: %',
                    partition_record.partition_name, SQLERRM;
        END;
    END LOOP;

    RAISE NOTICE 'Old partition cleanup completed';
END;
$$ LANGUAGE plpgsql;

-- Function to get partition statistics
CREATE OR REPLACE FUNCTION get_partition_statistics()
RETURNS TABLE (
    partition_name TEXT,
    partition_date DATE,
    row_count BIGINT,
    total_size TEXT,
    daily_runs BIGINT,
    monthly_runs BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        c.relname::TEXT,
        TO_DATE(REPLACE(SUBSTRING(c.relname FROM 'calculator_runs_(.*)'), '_', '-'), 'YYYY-MM-DD'),
        pg_class_size.reltuples::BIGINT,
        pg_size_pretty(pg_total_relation_size(c.oid)),
        (SELECT COUNT(*) FROM ONLY calculator_runs p
         WHERE p.tableoid = c.oid AND p.frequency = 'DAILY')::BIGINT,
        (SELECT COUNT(*) FROM ONLY calculator_runs p
         WHERE p.tableoid = c.oid AND p.frequency = 'MONTHLY')::BIGINT
    FROM pg_class c
    JOIN pg_inherits i ON c.oid = i.inhrelid
    JOIN pg_class parent ON i.inhparent = parent.oid
    LEFT JOIN LATERAL (
        SELECT reltuples FROM pg_class WHERE oid = c.oid
    ) pg_class_size ON true
    WHERE parent.relname = 'calculator_runs'
    AND c.relname LIKE 'calculator_runs_%'
    ORDER BY c.relname DESC;
END;
$$ LANGUAGE plpgsql;

-- ================================================================
-- AUTOMATED PARTITION MAINTENANCE
-- ================================================================

-- Create initial partitions
SELECT create_calculator_run_partitions();

-- Schedule automatic partition creation (via pg_cron if available)
-- Otherwise, call from application scheduler
COMMENT ON FUNCTION create_calculator_run_partitions() IS
'Call daily to create partitions for next 60 days';

COMMENT ON FUNCTION drop_old_calculator_run_partitions() IS
'Call weekly to drop partitions older than retention period';
```

## File: src/main/resources/db/migration/V5__daily_aggregations.sql
```sql
CREATE TABLE IF NOT EXISTS calculator_sli_daily (
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    day_cet DATE NOT NULL,
    total_runs INT DEFAULT 0,
    success_runs INT DEFAULT 0,
    sla_breaches INT DEFAULT 0,
    avg_duration_ms BIGINT DEFAULT 0,
    avg_start_min_cet INT DEFAULT 0,  -- Minutes since midnight CET (0-1439)
    avg_end_min_cet INT DEFAULT 0,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (calculator_id, tenant_id, day_cet),
    CONSTRAINT fk_daily_calculator FOREIGN KEY (calculator_id)
        REFERENCES calculators(calculator_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_calculator_sli_daily_recent
ON calculator_sli_daily(calculator_id, tenant_id, day_cet DESC);
```

## File: src/main/resources/db/migration/V6__sla_breach_events.sql
```sql
CREATE TABLE IF NOT EXISTS sla_breach_events (
    breach_id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) NOT NULL UNIQUE,  -- UNIQUE ensures idempotency
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    breach_type VARCHAR(50) NOT NULL,
    expected_value BIGINT,
    actual_value BIGINT,
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    alerted BOOLEAN DEFAULT false,
    alerted_at TIMESTAMPTZ,
    alert_status VARCHAR(20) DEFAULT 'PENDING'
        CHECK (alert_status IN ('PENDING', 'SENT', 'FAILED', 'RETRYING')),
    retry_count INT DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_breach_run FOREIGN KEY (run_id)
        REFERENCES calculator_runs(run_id) ON DELETE CASCADE
);

CREATE INDEX idx_sla_breach_events_unalerted ON sla_breach_events(created_at)
    WHERE alerted = false;
CREATE INDEX idx_sla_breach_events_calculator ON sla_breach_events(calculator_id, created_at DESC);
```

## File: src/main/resources/db/migration/V7__calculator_statistics.sql
```sql
CREATE TABLE IF NOT EXISTS calculator_statistics (
    stat_id BIGSERIAL PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    period_days INT NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    total_runs INT DEFAULT 0,
    successful_runs INT DEFAULT 0,
    failed_runs INT DEFAULT 0,
    avg_duration_ms BIGINT,
    min_duration_ms BIGINT,
    max_duration_ms BIGINT,
    avg_start_hour_cet DECIMAL(4, 2),
    avg_end_hour_cet DECIMAL(4, 2),
    sla_breaches INT DEFAULT 0,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_calculator_statistics_latest
    ON calculator_statistics(calculator_id, tenant_id, period_days, computed_at DESC);
```

## File: src/main/resources/db/migration/V8__idempotency_keys.sql
```sql
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_type VARCHAR(50) NOT NULL,
    request_payload JSONB,
    response_payload JSONB,
    response_status INT,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idempotency_keys_expires ON idempotency_keys(expires_at);
```

## File: src/main/resources/db/migration/V9__maintenance_functions.sql
```sql
CREATE OR REPLACE FUNCTION cleanup_expired_idempotency_keys()
RETURNS void AS $$
BEGIN
    DELETE FROM idempotency_keys WHERE expires_at < NOW();
END;
$$ LANGUAGE plpgsql;
```

## File: src/main/resources/application.yml
```yaml
# File: src/main/resources/application.yml
spring:
  application:
    name: observability-service-main
  
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/${POSTGRES_DB:observability}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      pool-name: ObservabilityHikariCP
      leak-detection-threshold: 60000

  # NO JPA configuration needed!

  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
    baseline-version: 0
    schemas: public
    validate-on-migrate: true

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
          min-idle: 2

  cache:
    type: redis
    redis:
      time-to-live: 900000  # 15 minutes default
      cache-null-values: false

  task:
    scheduling:
      pool:
        size: 5
        thread-name-prefix: scheduled-
    execution:
      pool:
        core-size: 5
        max-size: 10
        queue-capacity: 100
        thread-name-prefix: async-

# OpenTelemetry
otel:
  service:
    name: observability-service
  traces:
    exporter: ${OTEL_TRACES_EXPORTER:logging}
  metrics:
    exporter: ${OTEL_METRICS_EXPORTER:logging}

# Azure Monitor
applicationinsights:
  connection:
    string: ${APPLICATIONINSIGHTS_CONNECTION_STRING:}

# Actuator Management endpoints
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      environment: ${SPRING_PROFILES_ACTIVE:local}

# Custom properties: Observability Configuration
observability:
  sla:
    # OPTIMIZED: Live detection every 2 minutes (tolerance: few minutes)
    live-detection:
      enabled: true
      interval-ms: 120000        # Every 2 minutes (good balance)
      initial-delay-ms: 30000    # Start after 30 seconds

    # Early warning for approaching SLA
    early-warning:
      enabled: true
      interval-ms: 180000        # Every 3 minutes
      threshold-minutes: 10      # Warn if SLA within 10 minutes

  cache:
    eviction:
      enabled: true

  partitions:
    management:
      enabled: true
      create-cron: "0 0 1 * * *"  # Daily at 1 AM

# Resilience4j Configuration
resilience4j:
  circuitbreaker:
    instances:
      azureMonitorAlert:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
  retry:
    instances:
      azureMonitorAlert:
        maxAttempts: 3
        waitDuration: 1000ms


# Logging
logging:
  level:
    root: INFO
    com.company.observability: DEBUG
    org.flywaydb: DEBUG
    org.springframework.jdbc: DEBUG
    org.springframework.cache: TRACE
    org.springframework.cache.interceptor: TRACE
    org.springframework.data.redis.cache: TRACE
    io.lettuce.core: DEBUG
    pattern:
      console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} [%X{requestId}] - %msg%n"
      file: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} [%X{requestId}] - %msg%n"

# Springdoc OpenAPI
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method
    tags-sorter: alpha
```
