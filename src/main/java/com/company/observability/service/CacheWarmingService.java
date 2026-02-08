package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.event.*;
import com.company.observability.repository.CalculatorRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    private final RedisCalculatorCache redisCache;
    private final CalculatorRunRepository runRepository;

    /**
     * When run starts, invalidate cache so status shows RUNNING immediately
     */
    @EventListener
    @Async
    public void onRunStarted(RunStartedEvent event) {
        evictCacheForRun(event.getRun());
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
    private void evictCacheForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        var frequency = run.getFrequency();

        log.debug("Invalidating cache for calculator {} (started)", calculatorId);

        // Evict response caches for this calculator+frequency
        redisCache.evictStatusResponse(calculatorId, tenantId, frequency);
        // Evict recent runs ZSET for this frequency
        redisCache.evictRecentRuns(calculatorId, tenantId, frequency);
    }

    /**
     * Proactively warm cache with fresh data from database
     * This is faster than waiting for next query
     */
    private void warmCacheForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        var frequency = run.getFrequency();

        log.info("Warming cache for calculator {} (frequency: {})", calculatorId, frequency);

        try {
            // Warm recent runs ZSET via repository (write-through cache)
            runRepository.findRecentRuns(calculatorId, tenantId, frequency, 20);

            log.info("Successfully warmed cache for calculator {}", calculatorId);

        } catch (Exception e) {
            log.error("Failed to warm cache for calculator {}", calculatorId, e);
        }
    }
}
