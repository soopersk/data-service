package com.company.observability.service;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * FIXED: Use @TransactionalEventListener(AFTER_COMMIT) to prevent cache operations
 * before database commit
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CacheEvictionService {

    private final RedisCalculatorCache redisCache;
    private final MeterRegistry meterRegistry;

    /**
     * FIXED: Only evict after transaction commits
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        CalculatorRun run = event.getRun();

        try {
            // Evict status response cache for this frequency
            redisCache.evictStatusResponse(run.getCalculatorId(), run.getTenantId(), run.getFrequency());

            // FIXED: Reduced cardinality
            meterRegistry.counter("cache.evictions",
                    Tags.of(
                            Tag.of("event", "completed"),
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();

            log.debug("Evicted cache for {} after completion", run.getCalculatorId());
            
        } catch (Exception e) {
            log.error("Failed to evict cache after run completion", e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunStarted(RunStartedEvent event) {
        CalculatorRun run = event.getRun();

        try {
            redisCache.evictStatusResponse(run.getCalculatorId(), run.getTenantId(), run.getFrequency());

            meterRegistry.counter("cache.evictions",
                    Tags.of(
                            Tag.of("event", "started"),
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();
            
        } catch (Exception e) {
            log.error("Failed to evict cache after run start", e);
        }
    }

    /**
     * FIXED: Also update the ZSET cache when SLA breach is detected
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        CalculatorRun run = event.getRun();

        try {
            // FIXED: Update run in ZSET cache (not just evict response cache)
            redisCache.updateRunInCache(run);
            
            // Also evict status response cache
            redisCache.evictStatusResponse(run.getCalculatorId(), run.getTenantId(), run.getFrequency());

            meterRegistry.counter("cache.evictions",
                    Tags.of(
                            Tag.of("event", "sla_breached"),
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();
            
            log.debug("Updated cache for {} after SLA breach", run.getCalculatorId());
            
        } catch (Exception e) {
            log.error("Failed to update cache after SLA breach", e);
        }
    }
}