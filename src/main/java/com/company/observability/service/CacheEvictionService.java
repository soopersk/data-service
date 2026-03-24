package com.company.observability.service;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.*;
import com.company.observability.util.ObservabilityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(
        value = "observability.cache.legacy-eviction-listener.enabled",
        havingValue = "true",
        matchIfMissing = false
)
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

            meterRegistry.counter(ObservabilityConstants.CACHE_EVICTION_TOTAL,
                    Tags.of(
                            Tag.of("event", "completed"),
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();

            log.debug("event=cache_evict_complete calculator_id={} frequency={}", run.getCalculatorId(), run.getFrequency().name());

        } catch (Exception e) {
            log.error("event=cache_evict_failure trigger=completed calculator_id={}", run.getCalculatorId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunStarted(RunStartedEvent event) {
        CalculatorRun run = event.getRun();

        try {
            redisCache.evictStatusResponse(run.getCalculatorId(), run.getTenantId(), run.getFrequency());

            meterRegistry.counter(ObservabilityConstants.CACHE_EVICTION_TOTAL,
                    Tags.of(
                            Tag.of("event", "started"),
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();

        } catch (Exception e) {
            log.error("event=cache_evict_failure trigger=started calculator_id={}", run.getCalculatorId(), e);
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

            meterRegistry.counter(ObservabilityConstants.CACHE_EVICTION_TOTAL,
                    Tags.of(
                            Tag.of("event", "sla_breached"),
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();

            log.debug("event=cache_evict_sla_breach calculator_id={} frequency={}", run.getCalculatorId(), run.getFrequency().name());

        } catch (Exception e) {
            log.error("event=cache_evict_failure trigger=sla_breached calculator_id={}", run.getCalculatorId(), e);
        }
    }
}
