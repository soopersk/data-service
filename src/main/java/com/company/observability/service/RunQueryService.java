package com.company.observability.service;

import com.company.observability.domain.*;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.*;
import com.company.observability.exception.*;
import com.company.observability.repository.*;
import com.company.observability.util.TimeUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunQueryService {

    private final CalculatorRunRepository runRepository;
    private final CalculatorRepository calculatorRepository;
    private final ObjectMapper objectMapper;

    public List<RunSummaryResponse> getLastNRuns(String calculatorId, String tenantId, int limit) {
        Calculator calculator = calculatorRepository.findById(calculatorId)
                .orElseThrow(() -> new CalculatorNotFoundException(calculatorId));

        String frequency = calculator.getFrequency();
        return getLastNRunsCached(calculatorId, tenantId, limit, frequency);
    }

    /**
     * FIXED: Query directly from calculator_runs table with Redis caching
     * No materialized view needed - Redis provides the performance layer
     */
    @Cacheable(
            value = "recentRuns:#{#frequency}",
            key = "#calculatorId + '-' + #tenantId + '-' + #limit"
    )
    public List<RunSummaryResponse> getLastNRunsCached(
            String calculatorId, String tenantId, int limit, String frequency) {

        log.debug("Cache miss - fetching last {} runs from database for calculator {}",
                limit, calculatorId);

        // Direct query with partial index optimization (30-day window)
        List<CalculatorRun> runs = runRepository.findRecentRuns(
                calculatorId, tenantId, limit);

        return runs.stream()
                .map(this::mapToRunSummaryResponse)
                .collect(Collectors.toList());
    }

    /**
     * FIXED: Batch query directly from calculator_runs table
     * Uses window function for efficient multi-calculator queries
     */
    @Cacheable(
            value = "batchRecentRuns",
            key = "#calculatorIds.hashCode() + '-' + #tenantId + '-' + #limit"
    )
    public Map<String, List<RunSummaryResponse>> getBatchRecentRuns(
            List<String> calculatorIds, String tenantId, int limit) {

        log.debug("Batch query cache miss - fetching for {} calculators from database",
                calculatorIds.size());

        // Direct batch query with window function
        List<CalculatorRun> allRuns = runRepository.findBatchRecentRuns(
                calculatorIds, tenantId, limit);

        Map<String, List<RunSummaryResponse>> grouped = new HashMap<>();

        for (CalculatorRun run : allRuns) {
            RunSummaryResponse response = mapToRunSummaryResponse(run);
            grouped.computeIfAbsent(run.getCalculatorId(), k -> new ArrayList<>()).add(response);
        }

        return grouped;
    }

    @Cacheable(
            value = "avgRuntime",
            key = "#calculatorId + '-' + #tenantId"
    )
    public AverageRuntimeResponse getAverageRuntime(String calculatorId, String tenantId) {
        Calculator calculator = calculatorRepository.findById(calculatorId)
                .orElseThrow(() -> new CalculatorNotFoundException(calculatorId));

        String frequency = calculator.getFrequency();
        int lookbackDays = CalculatorFrequency.valueOf(frequency).getLookbackDays();

        log.debug("Calculating average runtime for {} calculator {} (lookback: {} days)",
                frequency, calculatorId, lookbackDays);

        Map<String, Object> stats = runRepository.calculateAverageRuntime(
                calculatorId, tenantId, lookbackDays);

        return buildAverageRuntimeResponse(calculatorId, stats, frequency, lookbackDays);
    }

    public RunDetailResponse getRunById(String runId, String tenantId) {
        CalculatorRun run = runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));

        if (!run.getTenantId().equals(tenantId)) {
            throw new TenantAccessDeniedException(tenantId, runId);
        }

        return toRunDetailResponse(run);
    }

    private RunSummaryResponse mapToRunSummaryResponse(CalculatorRun run) {
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

    private RunDetailResponse toRunDetailResponse(CalculatorRun run) {
        return RunDetailResponse.builder()
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
                .slaDurationMs(run.getSlaDurationMs())
                .slaEndHourCet(run.getSlaEndHourCet())
                .slaBreached(run.getSlaBreached())
                .slaBreachReason(run.getSlaBreachReason())
                .runParameters(parseRunParameters(run.getRunParameters()))
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }

    private AverageRuntimeResponse buildAverageRuntimeResponse(
            String calculatorId, Map<String, Object> stats, String frequency, int lookbackDays) {

        Instant now = Instant.now();
        Instant periodStart = now.minus(Duration.ofDays(lookbackDays));

        Integer totalRuns = getIntValue(stats, "total_runs");
        Integer successfulRuns = getIntValue(stats, "successful_runs");
        Integer failedRuns = getIntValue(stats, "failed_runs");
        Integer slaBreaches = getIntValue(stats, "sla_breaches");

        BigDecimal complianceRate = totalRuns > 0
                ? BigDecimal.valueOf((totalRuns - slaBreaches) * 100.0 / totalRuns)
                .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return AverageRuntimeResponse.builder()
                .calculatorId(calculatorId)
                .frequency(frequency)
                .lookbackDays(lookbackDays)
                .periodStart(periodStart)
                .periodEnd(now)
                .totalRuns(totalRuns)
                .successfulRuns(successfulRuns)
                .failedRuns(failedRuns)
                .avgDurationMs(getLongValue(stats, "avg_duration_ms"))
                .minDurationMs(getLongValue(stats, "min_duration_ms"))
                .maxDurationMs(getLongValue(stats, "max_duration_ms"))
                .avgDurationFormatted(TimeUtils.formatDuration(getLongValue(stats, "avg_duration_ms")))
                .avgStartHourCet(getBigDecimalValue(stats, "avg_start_hour_cet"))
                .avgEndHourCet(getBigDecimalValue(stats, "avg_end_hour_cet"))
                .avgStartTimeCet(TimeUtils.formatCetHour(getBigDecimalValue(stats, "avg_start_hour_cet")))
                .avgEndTimeCet(TimeUtils.formatCetHour(getBigDecimalValue(stats, "avg_end_hour_cet")))
                .slaBreaches(slaBreaches)
                .slaComplianceRate(complianceRate)
                .build();
    }

    private Map<String, Object> parseRunParameters(String jsonString) {
        if (jsonString == null) return null;

        try {
            return objectMapper.readValue(jsonString, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse run parameters", e);
            return null;
        }
    }

    private Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private BigDecimal getBigDecimalValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return null;
    }
}