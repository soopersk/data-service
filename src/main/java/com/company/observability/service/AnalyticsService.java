package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.domain.DailyAggregate;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.enums.SlaStatus;
import com.company.observability.dto.response.*;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.repository.DailyAggregateRepository;
import com.company.observability.repository.SlaBreachEventRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final DailyAggregateRepository dailyAggregateRepository;
    private final SlaBreachEventRepository slaBreachEventRepository;
    private final CalculatorRunRepository calculatorRunRepository;
    private final AnalyticsCacheService cacheService;

    private static final String CACHE_RUNTIME = "runtime";
    private static final String CACHE_SLA_CORE = "sla-core";
    private static final String CACHE_SLA_SUMMARY = "sla-summary";
    private static final String CACHE_TRENDS = "trends";
    private static final String CACHE_RUN_PERF = "run-perf";

    // ================================================================
    // Runtime Analytics
    // ================================================================

    public RuntimeAnalyticsResponse getRuntimeAnalytics(
            String calculatorId, String tenantId, int days, CalculatorFrequency frequency) {

        RuntimeAnalyticsResponse cached = cacheService.getFromCache(
                CACHE_RUNTIME, calculatorId, tenantId, frequency.name(), days,
                RuntimeAnalyticsResponse.class);
        if (cached != null) return cached;

        List<DailyAggregate> aggregates = dailyAggregateRepository
                .findRecentAggregates(calculatorId, tenantId, days);

        RuntimeAnalyticsResponse response = buildRuntimeResponse(
                calculatorId, days, frequency, aggregates);

        cacheService.putInCache(CACHE_RUNTIME, calculatorId, tenantId,
                frequency.name(), days, response);
        return response;
    }

    private RuntimeAnalyticsResponse buildRuntimeResponse(
            String calculatorId, int days, CalculatorFrequency frequency,
            List<DailyAggregate> aggregates) {

        if (aggregates.isEmpty()) {
            return new RuntimeAnalyticsResponse(
                    calculatorId, days, frequency.name(),
                    0, 0, 0, 0, 0.0,
                    Collections.emptyList());
        }

        // Weighted average: SUM(avg_duration * total_runs) / SUM(total_runs)
        long weightedSum = 0;
        int totalRuns = 0;
        int totalSuccess = 0;
        long minDuration = Long.MAX_VALUE;
        long maxDuration = Long.MIN_VALUE;

        List<RuntimeAnalyticsResponse.DailyDataPoint> dataPoints = new ArrayList<>();

        for (DailyAggregate agg : aggregates) {
            weightedSum += agg.avgDurationMs() * agg.totalRuns();
            totalRuns += agg.totalRuns();
            totalSuccess += agg.successRuns();
            minDuration = Math.min(minDuration, agg.avgDurationMs());
            maxDuration = Math.max(maxDuration, agg.avgDurationMs());

            dataPoints.add(new RuntimeAnalyticsResponse.DailyDataPoint(
                    agg.dayCet(), agg.avgDurationMs(), agg.totalRuns(), agg.successRuns()));
        }

        long avgDuration = totalRuns > 0 ? weightedSum / totalRuns : 0;
        double successRate = totalRuns > 0 ? (double) totalSuccess / totalRuns : 0.0;

        return new RuntimeAnalyticsResponse(
                calculatorId, days, frequency.name(),
                avgDuration,
                minDuration == Long.MAX_VALUE ? 0 : minDuration,
                maxDuration == Long.MIN_VALUE ? 0 : maxDuration,
                totalRuns,
                Math.round(successRate * 1000.0) / 1000.0,
                dataPoints);
    }

    // ================================================================
    // SLA Summary
    // ================================================================

    public SlaSummaryResponse getSlaSummary(
            String calculatorId, String tenantId, int days) {

        SlaSummaryResponse cached = cacheService.getFromCache(
                CACHE_SLA_SUMMARY, calculatorId, tenantId, days,
                SlaSummaryResponse.class);
        if (cached != null) return cached;

        SlaCoreData coreData = getSlaCoreData(calculatorId, tenantId, days);
        SlaSummaryResponse response = buildSlaSummaryResponse(
                calculatorId, days, coreData);

        cacheService.putInCache(CACHE_SLA_SUMMARY, calculatorId, tenantId,
                days, response);
        return response;
    }

    private SlaSummaryResponse buildSlaSummaryResponse(
            String calculatorId, int days, SlaCoreData coreData) {

        int greenDays = 0, amberDays = 0, redDays = 0;

        for (DailyAggregate agg : coreData.getAggregates()) {
            String worstSeverity = coreData.getWorstSeverityByDay().get(agg.dayCet());
            if (worstSeverity == null || agg.slaBreaches() == 0) {
                greenDays++;
            } else {
                if ("HIGH".equals(worstSeverity) || "CRITICAL".equals(worstSeverity)) {
                    redDays++;
                } else {
                    amberDays++;
                }
            }
        }

        return new SlaSummaryResponse(
                calculatorId, days, coreData.getTotalBreaches(),
                greenDays, amberDays, redDays,
                coreData.getBreachesBySeverity(), coreData.getBreachesByType());
    }

    // ================================================================
    // Trend Analysis
    // ================================================================

    public TrendAnalyticsResponse getTrends(
            String calculatorId, String tenantId, int days) {

        TrendAnalyticsResponse cached = cacheService.getFromCache(
                CACHE_TRENDS, calculatorId, tenantId, days,
                TrendAnalyticsResponse.class);
        if (cached != null) return cached;

        SlaCoreData coreData = getSlaCoreData(calculatorId, tenantId, days);
        TrendAnalyticsResponse response = buildTrendResponse(
                calculatorId, days, coreData);

        cacheService.putInCache(CACHE_TRENDS, calculatorId, tenantId,
                days, response);
        return response;
    }

    private TrendAnalyticsResponse buildTrendResponse(
            String calculatorId, int days, SlaCoreData coreData) {

        List<TrendAnalyticsResponse.TrendDataPoint> trends = coreData.getAggregates().stream()
                .map(agg -> {
                    String worstSeverity = coreData.getWorstSeverityByDay().get(agg.dayCet());
                    String slaStatus = classifyDay(agg, worstSeverity);

                    return new TrendAnalyticsResponse.TrendDataPoint(
                            agg.dayCet(), agg.avgDurationMs(), agg.totalRuns(),
                            agg.successRuns(), agg.slaBreaches(),
                            agg.avgStartMinCet(), agg.avgEndMinCet(), slaStatus);
                })
                .toList();

        return new TrendAnalyticsResponse(calculatorId, days, trends);
    }

    // ================================================================
    // SLA Breach Details (paginated, no caching)
    // ================================================================

    public PagedResponse<SlaBreachDetailResponse> getSlaBreachDetails(
            String calculatorId, String tenantId, int days,
            String severity, int page, int size, String cursor) {

        boolean legacyPageMode = (cursor == null || cursor.isBlank()) && page > 0;
        List<SlaBreachEvent> pageRows;

        if (legacyPageMode) {
            int offset = page * size;
            log.debug("event=sla_breach.query outcome=success mode=legacy_offset page={} size={}", page, size);
            pageRows = slaBreachEventRepository.findByCalculatorIdPaginated(
                    calculatorId, tenantId, days, severity, offset, size + 1);
        } else {
            KeysetCursor keysetCursor = decodeCursor(cursor);
            pageRows = slaBreachEventRepository
                    .findByCalculatorIdKeyset(calculatorId, tenantId, days, severity,
                            keysetCursor != null ? keysetCursor.createdAt() : null,
                            keysetCursor != null ? keysetCursor.breachId() : null,
                            size + 1);
        }

        boolean hasMore = pageRows.size() > size;
        List<SlaBreachEvent> breaches = hasMore
                ? pageRows.subList(0, size)
                : pageRows;
        long total = slaBreachEventRepository
                .countByCalculatorIdAndPeriod(calculatorId, tenantId, days, severity);

        List<SlaBreachDetailResponse> content = breaches.stream()
                .map(this::toBreachDetail)
                .toList();

        String nextCursor = null;
        if (hasMore && !breaches.isEmpty()) {
            SlaBreachEvent tail = breaches.get(breaches.size() - 1);
            nextCursor = encodeCursor(tail.getCreatedAt(), tail.getBreachId());
        }

        return new PagedResponse<>(content, page, size, total,
                (int) Math.ceil((double) total / size), nextCursor);
    }

    private SlaBreachDetailResponse toBreachDetail(SlaBreachEvent breach) {
        return new SlaBreachDetailResponse(
                breach.getBreachId(), breach.getRunId(), breach.getCalculatorId(),
                breach.getCalculatorName(), breach.getBreachType().name(),
                breach.getSeverity().name(), mapSeverityToTrafficLight(breach.getSeverity().name()),
                breach.getExpectedValue(), breach.getActualValue(), breach.getCreatedAt());
    }

    // ================================================================
    // Run Performance Data (raw domain, no formatting)
    // ================================================================

    public RunPerformanceData getRunPerformanceData(
            String calculatorId, String tenantId, int days, CalculatorFrequency frequency) {

        RunPerformanceData cached = cacheService.getFromCache(
                CACHE_RUN_PERF, calculatorId, tenantId, frequency.name(), days,
                RunPerformanceData.class);
        if (cached != null) return cached;

        List<RunWithSlaStatus> runs = calculatorRunRepository
                .findRunsWithSlaStatus(calculatorId, tenantId, frequency, days);

        RunPerformanceData response = buildRunPerformanceData(
                calculatorId, days, frequency, runs);

        cacheService.putInCache(CACHE_RUN_PERF, calculatorId, tenantId,
                frequency.name(), days, response);
        return response;
    }

    private RunPerformanceData buildRunPerformanceData(
            String calculatorId, int days, CalculatorFrequency frequency,
            List<RunWithSlaStatus> runs) {

        if (runs.isEmpty()) {
            return new RunPerformanceData(
                    calculatorId, null, frequency.name(), days, 0L,
                    0, 0, 0, 0, 0,
                    Collections.emptyList(), null, null);
        }

        RunWithSlaStatus latestRun = runs.get(runs.size() - 1);

        long totalDuration = 0;
        int completedCount = 0;
        int terminalRuns = 0;
        int runningRuns = 0;
        int slaMetCount = 0, lateCount = 0, veryLateCount = 0;

        List<RunPerformanceData.RunDataPoint> dataPoints = new ArrayList<>();

        for (RunWithSlaStatus run : runs) {
            if (run.status() == RunStatus.RUNNING) {
                runningRuns++;
            } else {
                terminalRuns++;
            }

            if (run.status() != RunStatus.RUNNING
                    && run.durationMs() != null
                    && run.durationMs() > 0) {
                totalDuration += run.durationMs();
                completedCount++;
            }

            String slaStatus = classifyRunSlaStatus(run);
            if (run.status() != RunStatus.RUNNING) {
                if (SlaStatus.SLA_MET.name().equals(slaStatus)) slaMetCount++;
                else if (SlaStatus.LATE.name().equals(slaStatus)) lateCount++;
                else if (SlaStatus.VERY_LATE.name().equals(slaStatus)) veryLateCount++;
            }

            dataPoints.add(new RunPerformanceData.RunDataPoint(
                    run.runId(),
                    run.reportingDate(),
                    run.startTime(),
                    run.endTime(),
                    run.durationMs(),
                    run.status().name(),
                    run.slaBreached(),
                    slaStatus));
        }

        long meanDuration = completedCount > 0 ? totalDuration / completedCount : 0;

        return new RunPerformanceData(
                calculatorId,
                latestRun.calculatorName(),
                frequency.name(),
                days,
                meanDuration,
                terminalRuns,
                runningRuns,
                slaMetCount,
                lateCount,
                veryLateCount,
                dataPoints,
                latestRun.estimatedStartTime(),
                latestRun.slaTime());
    }

    // ================================================================
    // SLA classification helpers
    // ================================================================

    /**
     * Per-run classification for performance card (SLA_MET / LATE / VERY_LATE)
     */
    private String classifyRunSlaStatus(RunWithSlaStatus run) {
        if (run.status() == RunStatus.RUNNING) {
            return SlaStatus.RUNNING.name();
        }
        if (!Boolean.TRUE.equals(run.slaBreached())) {
            return SlaStatus.SLA_MET.name();
        }
        if (run.severity() == null) {
            return SlaStatus.LATE.name(); // fallback for breach without severity record
        }
        return switch (run.severity()) {
            case HIGH, CRITICAL -> SlaStatus.VERY_LATE.name();
            default -> SlaStatus.LATE.name();
        };
    }

    /**
     * Per-day classification for trends/summary (GREEN / AMBER / RED)
     */
    private String classifyDay(DailyAggregate agg, String worstSeverity) {
        if (worstSeverity == null || agg.slaBreaches() == 0) {
            return SlaStatus.GREEN.name();
        }
        return mapSeverityToTrafficLight(worstSeverity);
    }

    private SlaCoreData getSlaCoreData(String calculatorId, String tenantId, int days) {
        SlaCoreData cached = cacheService.getFromCache(
                CACHE_SLA_CORE, calculatorId, tenantId, days, SlaCoreData.class
        );
        if (cached != null) {
            return cached;
        }

        List<DailyAggregate> aggregates = dailyAggregateRepository
                .findRecentAggregates(calculatorId, tenantId, days);
        Map<String, Integer> bySeverity = slaBreachEventRepository
                .countBySeverity(calculatorId, tenantId, days);
        Map<String, Integer> byType = slaBreachEventRepository
                .countByType(calculatorId, tenantId, days);
        Map<LocalDate, String> worstSeverityByDay = slaBreachEventRepository
                .findWorstSeverityByDay(calculatorId, tenantId, days);

        int totalBreaches = bySeverity.values().stream()
                .mapToInt(Integer::intValue)
                .sum();

        SlaCoreData coreData = SlaCoreData.builder()
                .aggregates(aggregates)
                .breachesBySeverity(bySeverity)
                .breachesByType(byType)
                .worstSeverityByDay(worstSeverityByDay)
                .totalBreaches(totalBreaches)
                .build();

        cacheService.putInCache(CACHE_SLA_CORE, calculatorId, tenantId, days, coreData);
        return coreData;
    }

    private String mapSeverityToTrafficLight(String severity) {
        if (severity == null) return SlaStatus.AMBER.name();
        return switch (severity) {
            case "HIGH", "CRITICAL" -> SlaStatus.RED.name();
            default -> SlaStatus.AMBER.name();
        };
    }

    private String encodeCursor(java.time.Instant createdAt, long breachId) {
        String payload = createdAt.toEpochMilli() + ":" + breachId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private KeysetCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );

            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) {
                return null;
            }

            long epochMillis = Long.parseLong(parts[0]);
            long breachId = Long.parseLong(parts[1]);
            return new KeysetCursor(java.time.Instant.ofEpochMilli(epochMillis), breachId);
        } catch (Exception e) {
            log.warn("event=sla_breach.cursor_decode outcome=failure cursor={}", cursor);
            return null;
        }
    }

    private record KeysetCursor(java.time.Instant createdAt, long breachId) {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class SlaCoreData implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<DailyAggregate> aggregates;
        private Map<String, Integer> breachesBySeverity;
        private Map<String, Integer> breachesByType;
        private Map<LocalDate, String> worstSeverityByDay;
        private int totalBreaches;
    }
}
