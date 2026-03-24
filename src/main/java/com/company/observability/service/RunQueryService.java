package com.company.observability.service;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.*;
import com.company.observability.exception.DomainNotFoundException;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.TimeUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Query service
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RunQueryService {

    private final CalculatorRunRepository runRepository;
    private final RedisCalculatorCache redisCache;
    private final MeterRegistry meterRegistry;

    public CalculatorStatusResponse getCalculatorStatus(
            String calculatorId, String tenantId, CalculatorFrequency frequency, 
            int historyLimit, boolean bypassCache) {

        // Check cache (unless bypassed)
        if (!bypassCache) {
            Optional<CalculatorStatusResponse> cachedResponse =
                    redisCache.getStatusResponse(calculatorId, tenantId, frequency, historyLimit);

            if (cachedResponse.isPresent()) {
                meterRegistry.counter("query.calculator_status.cache_hit",
                        Tags.of(
                                Tag.of("tier", "response_cache"),
                                Tag.of("frequency", frequency.name())
                        )
                ).increment();
                return cachedResponse.get();
            }
        }

        // Query with partition pruning
        List<CalculatorRun> runs = runRepository.findRecentRuns(
                calculatorId, tenantId, frequency, historyLimit + 1);

        if (runs.isEmpty()) {
            throw new DomainNotFoundException("Calculator not found: " + calculatorId);
        }

        // Build response
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

        // Cache (unless bypassed)
        if (!bypassCache) {
            redisCache.cacheStatusResponse(calculatorId, tenantId, frequency, historyLimit, response);
        }

        meterRegistry.counter("query.calculator_status.cache_miss",
                Tags.of(
                        Tag.of("tier", "response_cache"),
                        Tag.of("frequency", frequency.name())
                )
        ).increment();

        return response;
    }

    public List<CalculatorStatusResponse> getBatchCalculatorStatus(
            List<String> calculatorIds, String tenantId, CalculatorFrequency frequency, 
            int historyLimit, boolean allowStale) {

        long startTime = System.currentTimeMillis();

        // 1. Determine cached responses (effectively final)
        final Map<String, CalculatorStatusResponse> cachedResponses = allowStale
                ? redisCache.getBatchStatusResponses(calculatorIds, tenantId, frequency, historyLimit)
                : Collections.emptyMap();

        // 2. Determine misses based on the hits (effectively final)
        final List<String> cacheMisses = calculatorIds.stream()
                .filter(id -> !cachedResponses.containsKey(id))
                .collect(Collectors.toList());

        log.debug("BATCH ({}): {} cache hits, {} misses (allowStale: {})",
                frequency, cachedResponses.size(), cacheMisses.size(), allowStale);

        // Query missing calculators
        Map<String, CalculatorStatusResponse> freshResponses = new HashMap<>();

        if (!cacheMisses.isEmpty()) {
            Map<String, List<CalculatorRun>> runsByCalculator =
                    runRepository.findBatchRecentRunsDbOnly(cacheMisses, tenantId, frequency, historyLimit + 1);

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

            // Cache if stale data is acceptable
            if (allowStale) {
                redisCache.cacheBatchStatusResponses(freshResponses, tenantId, frequency, historyLimit);
            }
        }

        // Combine results
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
                Tags.of(
                        Tag.of("tier", "response_cache"),
                        Tag.of("frequency", frequency.name())
                )
        ).increment();

        meterRegistry.timer("query.batch_status.duration",
                Tags.of(
                        Tag.of("frequency", frequency.name())
                )
        ).record(java.time.Duration.ofMillis(duration));

        meterRegistry.summary("query.batch_status.batch_size",
                Tags.of(
                    Tag.of("frequency", frequency.name()),
                    Tag.of("allow_stale", String.valueOf(allowStale))
                )
        ).record(calculatorIds.size());

        log.debug("Batch query ({}) completed in {}ms: {}/{} calculators",
                frequency, duration, result.size(), calculatorIds.size());

        return result;
    }

    private RunStatusInfo mapToRunStatusInfo(CalculatorRun run) {
        return new RunStatusInfo(
                run.getRunId(), run.getStatus().name(),
                run.getStartTime(), run.getEndTime(),
                run.getEstimatedStartTime(), run.getEstimatedEndTime(),
                run.getSlaTime(), run.getDurationMs(),
                TimeUtils.formatDuration(run.getDurationMs()),
                run.getSlaBreached(), run.getSlaBreachReason());
    }
}
