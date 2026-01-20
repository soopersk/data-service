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