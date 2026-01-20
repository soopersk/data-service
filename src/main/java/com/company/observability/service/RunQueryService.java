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