package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.event.*;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.MdcContextUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

import static com.company.observability.util.ObservabilityConstants.*;

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
    private final MeterRegistry meterRegistry;

    /**
     * When run starts, invalidate cache so status shows RUNNING immediately
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunStarted(RunStartedEvent event) {
        CalculatorRun run = event.getRun();
        Map<String, String> snapshot = MdcContextUtil.setCalculatorContext(run.getCalculatorId(), "-");
        try {
            evictCacheForRun(run);
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    /**
     * When run completes, immediately warm the cache with fresh data
     * This prevents the next UI query from hitting the database
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        CalculatorRun run = event.getRun();
        Map<String, String> snapshot = MdcContextUtil.setCalculatorContext(run.getCalculatorId(), "-");
        try {
            evictCacheForRun(run);
            warmCacheForRun(run);
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    /**
     * When SLA breaches, update cache with breach information
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        CalculatorRun run = event.getRun();
        Map<String, String> snapshot = MdcContextUtil.setCalculatorContext(run.getCalculatorId(), "-");
        try {
            redisCache.updateRunInCache(run);
            redisCache.evictStatusResponse(run.getCalculatorId(), run.getTenantId(), run.getFrequency());
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    /**
     * Invalidate cache entries for a calculator
     */
    private void evictCacheForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        var frequency = run.getFrequency();

        log.debug("event=cache.evict outcome=success freq={}", frequency);

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

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Warm recent runs ZSET via repository (write-through cache)
            runRepository.findRecentRuns(calculatorId, tenantId, frequency, 20);

            sample.stop(meterRegistry.timer(CACHE_WARM_DURATION, "event", "completed"));
            log.debug("event=cache.warm outcome=success freq={}", frequency);

        } catch (Exception e) {
            sample.stop(meterRegistry.timer(CACHE_WARM_DURATION, "event", "completed"));
            meterRegistry.counter(CACHE_WARM_FAILURE, "event", "completed").increment();
            log.error("event=cache.warm outcome=failure freq={}", frequency, e);
        }
    }
}
