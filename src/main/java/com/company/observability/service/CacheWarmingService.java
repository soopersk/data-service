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