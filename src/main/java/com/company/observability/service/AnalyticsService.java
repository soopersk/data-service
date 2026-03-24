package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.domain.DailyAggregate;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.*;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.repository.DailyAggregateRepository;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.util.TimeUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    private static final String CACHE_PERF_CARD = "perf-card";

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH);

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
            return RuntimeAnalyticsResponse.builder()
                    .calculatorId(calculatorId)
                    .periodDays(days)
                    .frequency(frequency.name())
                    .dataPoints(Collections.emptyList())
                    .build();
        }

        // Weighted average: SUM(avg_duration * total_runs) / SUM(total_runs)
        long weightedSum = 0;
        int totalRuns = 0;
        int totalSuccess = 0;
        long minDuration = Long.MAX_VALUE;
        long maxDuration = Long.MIN_VALUE;

        List<RuntimeAnalyticsResponse.DailyDataPoint> dataPoints = new ArrayList<>();

        for (DailyAggregate agg : aggregates) {
            weightedSum += agg.getAvgDurationMs() * agg.getTotalRuns();
            totalRuns += agg.getTotalRuns();
            totalSuccess += agg.getSuccessRuns();
            minDuration = Math.min(minDuration, agg.getAvgDurationMs());
            maxDuration = Math.max(maxDuration, agg.getAvgDurationMs());

            dataPoints.add(RuntimeAnalyticsResponse.DailyDataPoint.builder()
                    .date(agg.getDayCet())
                    .avgDurationMs(agg.getAvgDurationMs())
                    .totalRuns(agg.getTotalRuns())
                    .successRuns(agg.getSuccessRuns())
                    .build());
        }

        long avgDuration = totalRuns > 0 ? weightedSum / totalRuns : 0;
        double successRate = totalRuns > 0 ? (double) totalSuccess / totalRuns : 0.0;

        return RuntimeAnalyticsResponse.builder()
                .calculatorId(calculatorId)
                .periodDays(days)
                .frequency(frequency.name())
                .avgDurationMs(avgDuration)
                .avgDurationFormatted(TimeUtils.formatDuration(avgDuration))
                .minDurationMs(minDuration == Long.MAX_VALUE ? 0 : minDuration)
                .maxDurationMs(maxDuration == Long.MIN_VALUE ? 0 : maxDuration)
                .totalRuns(totalRuns)
                .successRate(Math.round(successRate * 1000.0) / 1000.0)
                .dataPoints(dataPoints)
                .build();
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
            String worstSeverity = coreData.getWorstSeverityByDay().get(agg.getDayCet());
            if (worstSeverity == null || agg.getSlaBreaches() == 0) {
                greenDays++;
            } else {
                if ("HIGH".equals(worstSeverity) || "CRITICAL".equals(worstSeverity)) {
                    redDays++;
                } else {
                    amberDays++;
                }
            }
        }

        return SlaSummaryResponse.builder()
                .calculatorId(calculatorId)
                .periodDays(days)
                .totalBreaches(coreData.getTotalBreaches())
                .greenDays(greenDays)
                .amberDays(amberDays)
                .redDays(redDays)
                .breachesBySeverity(coreData.getBreachesBySeverity())
                .breachesByType(coreData.getBreachesByType())
                .build();
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
                    String worstSeverity = coreData.getWorstSeverityByDay().get(agg.getDayCet());
                    String slaStatus = classifyDay(agg, worstSeverity);

                    return TrendAnalyticsResponse.TrendDataPoint.builder()
                            .date(agg.getDayCet())
                            .avgDurationMs(agg.getAvgDurationMs())
                            .totalRuns(agg.getTotalRuns())
                            .successRuns(agg.getSuccessRuns())
                            .slaBreaches(agg.getSlaBreaches())
                            .avgStartMinCet(agg.getAvgStartMinCet())
                            .avgEndMinCet(agg.getAvgEndMinCet())
                            .slaStatus(slaStatus)
                            .build();
                })
                .toList();

        return TrendAnalyticsResponse.builder()
                .calculatorId(calculatorId)
                .periodDays(days)
                .trends(trends)
                .build();
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
            log.debug("Using legacy offset pagination for SLA breaches (page={}, size={})", page, size);
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

        return PagedResponse.<SlaBreachDetailResponse>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(total)
                .totalPages((int) Math.ceil((double) total / size))
                .nextCursor(nextCursor)
                .build();
    }

    private SlaBreachDetailResponse toBreachDetail(SlaBreachEvent breach) {
        return SlaBreachDetailResponse.builder()
                .breachId(breach.getBreachId())
                .runId(breach.getRunId())
                .calculatorId(breach.getCalculatorId())
                .calculatorName(breach.getCalculatorName())
                .breachType(breach.getBreachType().name())
                .severity(breach.getSeverity().name())
                .slaStatus(mapSeverityToTrafficLight(breach.getSeverity().name()))
                .expectedValue(breach.getExpectedValue())
                .actualValue(breach.getActualValue())
                .createdAt(breach.getCreatedAt())
                .build();
    }

    // ================================================================
    // Performance Card
    // ================================================================

    public PerformanceCardResponse getPerformanceCard(
            String calculatorId, String tenantId, int days, CalculatorFrequency frequency) {

        PerformanceCardResponse cached = cacheService.getFromCache(
                CACHE_PERF_CARD, calculatorId, tenantId, frequency.name(), days,
                PerformanceCardResponse.class);
        if (cached != null) return cached;

        List<RunWithSlaStatus> runs = calculatorRunRepository
                .findRunsWithSlaStatus(calculatorId, tenantId, frequency, days);

        PerformanceCardResponse response = buildPerformanceCardResponse(
                calculatorId, days, frequency, runs);

        cacheService.putInCache(CACHE_PERF_CARD, calculatorId, tenantId,
                frequency.name(), days, response);
        return response;
    }

    private PerformanceCardResponse buildPerformanceCardResponse(
            String calculatorId, int days, CalculatorFrequency frequency,
            List<RunWithSlaStatus> runs) {

        if (runs.isEmpty()) {
            return PerformanceCardResponse.builder()
                    .calculatorId(calculatorId)
                    .periodDays(days)
                    .slaSummary(PerformanceCardResponse.SlaSummaryPct.builder()
                            .totalRuns(0).build())
                    .runs(Collections.emptyList())
                    .build();
        }

        // Extract schedule from most recent run (last in chronologically ordered list)
        RunWithSlaStatus latestRun = runs.get(runs.size() - 1);

        // Compute mean duration (weighted by completed runs only)
        long totalDuration = 0;
        int completedCount = 0;
        int slaMetCount = 0, lateCount = 0, veryLateCount = 0;

        List<PerformanceCardResponse.RunBar> runBars = new ArrayList<>();

        for (RunWithSlaStatus run : runs) {
            if (run.getDurationMs() != null && run.getDurationMs() > 0) {
                totalDuration += run.getDurationMs();
                completedCount++;
            }

            String slaStatus = classifyRunSlaStatus(run);
            switch (slaStatus) {
                case "SLA_MET" -> slaMetCount++;
                case "LATE" -> lateCount++;
                case "VERY_LATE" -> veryLateCount++;
            }

            runBars.add(buildRunBar(run, slaStatus));
        }

        long meanDuration = completedCount > 0 ? totalDuration / completedCount : 0;
        int totalRuns = runs.size();

        // SLA percentages (must sum to 100%)
        PerformanceCardResponse.SlaSummaryPct slaSummary =
                PerformanceCardResponse.SlaSummaryPct.builder()
                        .totalRuns(totalRuns)
                        .slaMetCount(slaMetCount)
                        .slaMetPct(pct(slaMetCount, totalRuns))
                        .lateCount(lateCount)
                        .latePct(pct(lateCount, totalRuns))
                        .veryLateCount(veryLateCount)
                        .veryLatePct(pct(veryLateCount, totalRuns))
                        .build();

        // Reference lines from latest run
        BigDecimal slaStartHour = TimeUtils.calculateCetHour(latestRun.getEstimatedStartTime());
        BigDecimal slaEndHour = TimeUtils.calculateCetHour(latestRun.getSlaTime());

        // Schedule info
        String startTimeCet = slaStartHour != null
                ? TimeUtils.formatCetHour(slaStartHour) : null;

        return PerformanceCardResponse.builder()
                .calculatorId(calculatorId)
                .calculatorName(latestRun.getCalculatorName())
                .schedule(PerformanceCardResponse.ScheduleInfo.builder()
                        .estimatedStartTimeCet(startTimeCet)
                        .frequency(frequency.name())
                        .build())
                .periodDays(days)
                .meanDurationMs(meanDuration)
                .meanDurationFormatted(TimeUtils.formatDuration(meanDuration))
                .slaSummary(slaSummary)
                .runs(runBars)
                .referenceLines(PerformanceCardResponse.ReferenceLines.builder()
                        .slaStartHourCet(slaStartHour)
                        .slaEndHourCet(slaEndHour)
                        .build())
                .build();
    }

    private PerformanceCardResponse.RunBar buildRunBar(RunWithSlaStatus run, String slaStatus) {
        String dateFormatted = run.getReportingDate() != null
                ? run.getReportingDate().format(DATE_FORMATTER) : null;

        BigDecimal startHour = TimeUtils.calculateCetHour(run.getStartTime());
        BigDecimal endHour = TimeUtils.calculateCetHour(run.getEndTime());

        String startCet = startHour != null
                ? TimeUtils.formatCetHour(startHour) + " CET" : null;
        String endCet = endHour != null
                ? TimeUtils.formatCetHour(endHour) + " CET" : null;

        return PerformanceCardResponse.RunBar.builder()
                .runId(run.getRunId())
                .reportingDate(run.getReportingDate())
                .dateFormatted(dateFormatted)
                .startHourCet(startHour)
                .endHourCet(endHour)
                .startTimeCet(startCet)
                .endTimeCet(endCet)
                .durationMs(run.getDurationMs() != null ? run.getDurationMs() : 0)
                .durationFormatted(TimeUtils.formatDuration(run.getDurationMs()))
                .slaStatus(slaStatus)
                .build();
    }

    // ================================================================
    // SLA classification helpers
    // ================================================================

    /**
     * Per-run classification for performance card (SLA_MET / LATE / VERY_LATE)
     */
    private String classifyRunSlaStatus(RunWithSlaStatus run) {
        if (!Boolean.TRUE.equals(run.getSlaBreached())) {
            return "SLA_MET";
        }
        if (run.getSeverity() == null) {
            return "LATE"; // fallback for breach without severity record
        }
        return switch (run.getSeverity()) {
            case "HIGH", "CRITICAL" -> "VERY_LATE";
            default -> "LATE";
        };
    }

    /**
     * Per-day classification for trends/summary (GREEN / AMBER / RED)
     */
    private String classifyDay(DailyAggregate agg, String worstSeverity) {
        if (worstSeverity == null || agg.getSlaBreaches() == 0) {
            return "GREEN";
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
        if (severity == null) return "AMBER";
        return switch (severity) {
            case "HIGH", "CRITICAL" -> "RED";
            default -> "AMBER";
        };
    }

    private double pct(int count, int total) {
        if (total == 0) return 0.0;
        return Math.round((double) count / total * 1000.0) / 10.0;
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
            log.warn("Invalid SLA breach cursor provided: {}", cursor);
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
