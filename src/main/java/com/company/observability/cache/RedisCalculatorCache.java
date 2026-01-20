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