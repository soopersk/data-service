package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.DailyAggregate;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.enums.SlaStatus;
import com.company.observability.dto.response.*;
import com.company.observability.service.projection.LogicalRunGrouper;
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
    private final CalculatorProfileService calculatorProfileService;
    private final com.company.observability.config.DurationBasedSlaProperties slaProperties;
    private final SlaProperties slaModeProperties;
    private final CalculatorNameResolver nameResolver;

    private static final String CACHE_RUNTIME = "runtime";
    private static final String CACHE_SLA_CORE = "sla-core";
    private static final String CACHE_SLA_SUMMARY = "sla-summary";
    private static final String CACHE_TRENDS = "trends";
    private static final String CACHE_RUN_PERF = "run-perf";
    private static final String CACHE_EXECUTIONS = "executions";

    // ================================================================
    // Runtime Analytics
    // ================================================================

    public RuntimeAnalyticsResponse getRuntimeAnalytics(
            String calculatorId, int days, Frequency frequency) {

        RuntimeAnalyticsResponse cached = cacheService.getFromCache(
                CACHE_RUNTIME, calculatorId, frequency.name(), days,
                RuntimeAnalyticsResponse.class);
        if (cached != null) return cached;

        List<DailyAggregate> aggregates = dailyAggregateRepository
                .findRecentAggregates(calculatorId, days);

        RuntimeAnalyticsResponse response = buildRuntimeResponse(
                calculatorId, days, frequency, aggregates);

        cacheService.putInCache(CACHE_RUNTIME, calculatorId,
                frequency.name(), days, response);
        return response;
    }

    private RuntimeAnalyticsResponse buildRuntimeResponse(
            String calculatorId, int days, Frequency frequency,
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
                    agg.reportingDate(), agg.avgDurationMs(), agg.totalRuns(), agg.successRuns()));
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
            String calculatorId, int days) {

        SlaSummaryResponse cached = cacheService.getFromCache(
                CACHE_SLA_SUMMARY, calculatorId, days,
                SlaSummaryResponse.class);
        if (cached != null) return cached;

        SlaCoreData coreData = getSlaCoreData(calculatorId, days);
        SlaSummaryResponse response = buildSlaSummaryResponse(
                calculatorId, days, coreData);

        cacheService.putInCache(CACHE_SLA_SUMMARY, calculatorId,
                days, response);
        return response;
    }

    private SlaSummaryResponse buildSlaSummaryResponse(
            String calculatorId, int days, SlaCoreData coreData) {

        int greenDays = 0, amberDays = 0, redDays = 0;

        for (DailyAggregate agg : coreData.getAggregates()) {
            String worstBand = coreData.getWorstBandByDay().get(agg.reportingDate());
            if (worstBand == null || agg.slaBreaches() == 0) {
                greenDays++;
            } else {
                if ("FAILED".equals(worstBand) || "VERY_LATE".equals(worstBand)) {
                    redDays++;
                } else {
                    amberDays++;
                }
            }
        }

        return new SlaSummaryResponse(
                calculatorId, days, coreData.getTotalBreaches(),
                greenDays, amberDays, redDays,
                coreData.getBreachesByBand(), coreData.getBreachesByType());
    }

    // ================================================================
    // Trend Analysis
    // ================================================================

    public TrendAnalyticsResponse getTrends(
            String calculatorId, int days) {

        TrendAnalyticsResponse cached = cacheService.getFromCache(
                CACHE_TRENDS, calculatorId, days,
                TrendAnalyticsResponse.class);
        if (cached != null) return cached;

        SlaCoreData coreData = getSlaCoreData(calculatorId, days);
        TrendAnalyticsResponse response = buildTrendResponse(
                calculatorId, days, coreData);

        cacheService.putInCache(CACHE_TRENDS, calculatorId,
                days, response);
        return response;
    }

    private TrendAnalyticsResponse buildTrendResponse(
            String calculatorId, int days, SlaCoreData coreData) {

        List<TrendAnalyticsResponse.TrendDataPoint> trends = coreData.getAggregates().stream()
                .map(agg -> {
                    String worstBand = coreData.getWorstBandByDay().get(agg.reportingDate());
                    String slaStatus = classifyDay(agg, worstBand);

                    return new TrendAnalyticsResponse.TrendDataPoint(
                            agg.reportingDate(), agg.avgDurationMs(), agg.totalRuns(),
                            agg.successRuns(), agg.slaBreaches(),
                            agg.avgStartMinUtc(), agg.avgEndMinUtc(), slaStatus);
                })
                .toList();

        return new TrendAnalyticsResponse(calculatorId, days, trends);
    }

    // ================================================================
    // SLA Breach Details (paginated, no caching)
    // ================================================================

    public PagedResponse<SlaBreachDetailResponse> getSlaBreachDetails(
            String calculatorId, int days,
            String severity, int page, int size, String cursor) {

        boolean legacyPageMode = (cursor == null || cursor.isBlank()) && page > 0;
        List<SlaBreachEvent> pageRows;

        if (legacyPageMode) {
            int offset = page * size;
            log.debug("event=sla_breach.query outcome=success mode=legacy_offset page={} size={}", page, size);
            pageRows = slaBreachEventRepository.findByCalculatorIdPaginated(
                    calculatorId, days, severity, offset, size + 1);
        } else {
            KeysetCursor keysetCursor = decodeCursor(cursor);
            pageRows = slaBreachEventRepository
                    .findByCalculatorIdKeyset(calculatorId, days, severity,
                            keysetCursor != null ? keysetCursor.createdAt() : null,
                            keysetCursor != null ? keysetCursor.breachId() : null,
                            size + 1);
        }

        boolean hasMore = pageRows.size() > size;
        List<SlaBreachEvent> breaches = hasMore
                ? pageRows.subList(0, size)
                : pageRows;
        long total = slaBreachEventRepository
                .countByCalculatorIdAndPeriod(calculatorId, days, severity);

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
        // band is not stored on sla_breach_events; null until a JOIN is added to the paged query
        return new SlaBreachDetailResponse(
                breach.getBreachId(), breach.getRunId(), breach.getCalculatorId(),
                breach.getCalculatorName(), breach.getBreachType().name(),
                null, null,
                breach.getExpectedValue(), breach.getActualValue(), breach.getCreatedAt());
    }

    // ================================================================
    // Run Performance Data (raw domain, no formatting)
    // ================================================================

    public RunPerformanceData getRunPerformanceData(
            String calculatorId, int days, Frequency frequency) {

        RunPerformanceData cached = cacheService.getFromCache(
                CACHE_RUN_PERF, calculatorId, frequency.name(), days,
                RunPerformanceData.class);
        if (cached != null) return cached;

        List<RunWithSlaStatus> runs = calculatorRunRepository
                .findRunsWithSlaStatus(calculatorId, frequency, days);

        RunPerformanceData response = buildRunPerformanceData(
                calculatorId, days, frequency, runs);

        cacheService.putInCache(CACHE_RUN_PERF, calculatorId,
                frequency.name(), days, response);
        return response;
    }

    private RunPerformanceData buildRunPerformanceData(
            String calculatorId, int days, Frequency frequency,
            List<RunWithSlaStatus> runs) {

        if (runs.isEmpty()) {
            return new RunPerformanceData(
                    calculatorId, null, frequency.name(), days, 0L,
                    0, 0, 0, 0, 0,
                    Collections.emptyList(), null, null);
        }

        // Collapse split runs that share a correlationId into one logical run each.
        List<LogicalRunGrouper.LogicalRun> logicalRuns = LogicalRunGrouper.groupWithSla(runs);

        RunWithSlaStatus latestRaw = runs.get(runs.size() - 1);

        long totalDuration = 0;
        int completedCount = 0;
        int terminalRuns = 0;
        int runningRuns = 0;
        int slaMetCount = 0, lateCount = 0, veryLateCount = 0;

        List<RunPerformanceData.RunDataPoint> dataPoints = new ArrayList<>();

        for (LogicalRunGrouper.LogicalRun lr : logicalRuns) {
            boolean isRunning = RunStatus.RUNNING.name().equals(lr.status());

            if (isRunning) {
                runningRuns++;
            } else {
                terminalRuns++;
            }

            if (!isRunning && lr.wallClockDurationMs() != null && lr.wallClockDurationMs() > 0) {
                totalDuration += lr.wallClockDurationMs();
                completedCount++;
            }

            if (!isRunning) {
                if ("ON_TIME".equals(lr.slaStatus())) slaMetCount++;
                else if ("LATE".equals(lr.slaStatus())) lateCount++;
                else if ("VERY_LATE".equals(lr.slaStatus())) veryLateCount++;
            }

            dataPoints.add(new RunPerformanceData.RunDataPoint(
                    lr.runId(),
                    lr.reportingDate(),
                    lr.startTime(),
                    lr.endTime(),
                    lr.wallClockDurationMs(),
                    lr.status(),
                    lr.slaStatus(),   // slaBand — ON_TIME / LATE / VERY_LATE from LogicalRun
                    lr.slaStatus(),
                    lr.subRunIds(),
                    lr.estimatedStartTime(),
                    lr.slaTime(),
                    lr.runNumber(),
                    lr.expectedDurationMs()));
        }

        long meanDuration = completedCount > 0 ? totalDuration / completedCount : 0;

        return new RunPerformanceData(
                calculatorId,
                latestRaw.calculatorName(),
                frequency.name(),
                days,
                meanDuration,
                terminalRuns,
                runningRuns,
                slaMetCount,
                lateCount,
                veryLateCount,
                dataPoints,
                latestRaw.estimatedStartTime(),
                latestRaw.slaTime());
    }

    // ================================================================
    // Run Executions (raw, no grouping)
    // ================================================================

    public RunPerformanceData getRunExecutions(
            String calculatorId, int days, Frequency frequency) {
        return getRunExecutions(calculatorId, days, frequency, null);
    }

    public RunPerformanceData getRunExecutions(
            String calculatorId, int days, Frequency frequency, String runNumber) {

        List<RunWithSlaStatus> rawRuns = calculatorRunRepository
                .findRunsWithSlaStatus(calculatorId, frequency, days, runNumber);

        return buildExecutionsResponse(calculatorId, rawRuns, days, frequency);
    }

    /**
     * Name-keyed variant of {@link #getRunExecutions(String, String, int, Frequency, String)}.
     * Queries by calculator_name; the envelope's calculatorId field carries the same name,
     * so no upstream UUID appears in the response.
     */
    public RunPerformanceData getRunExecutionsByName(
            String calculatorName, int days, Frequency frequency, String runNumber,
            LocalDate asOfDate) {

        // Normalize blank → null so empty ?run_number= means "all runs" (not filter on empty string)
        String rn = (runNumber == null || runNumber.isBlank()) ? null : runNumber;

        // Cache is keyed by the alias/name as supplied by the caller
        RunPerformanceData cached = cacheService.getFromCache(
                CACHE_EXECUTIONS, calculatorName, frequency.name(), days, rn,
                asOfDate, RunPerformanceData.class);
        if (cached != null) {
            log.debug("event=executions.cache outcome=hit calculatorName={} frequency={} days={} runNumber={} asOfDate={}",
                    calculatorName, frequency, days, rn, asOfDate);
            return cached;
        }

        // Expand alias to real DB calculator_name values; unknown names pass through unchanged
        List<String> realNames = nameResolver.resolve(calculatorName);

        log.debug("event=executions.db_fetch outcome=start calculatorName={} realNames={} frequency={} days={} runNumber={} asOfDate={}",
                calculatorName, realNames, frequency, days, rn, asOfDate);

        List<RunWithSlaStatus> rawRuns = realNames.stream()
                .flatMap(name -> calculatorRunRepository.findRunsByName(name, frequency, days, rn, asOfDate).stream())
                .sorted(Comparator.comparing(RunWithSlaStatus::reportingDate)
                        .thenComparing(RunWithSlaStatus::startTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        RunPerformanceData response = buildExecutionsResponse(calculatorName, rawRuns, days, frequency);
        cacheService.putInCache(CACHE_EXECUTIONS, calculatorName, frequency.name(), days, rn, asOfDate, response);
        return response;
    }

    private RunPerformanceData buildExecutionsResponse(
            String calculatorKey,
            List<RunWithSlaStatus> rawRuns,
            int days,
            Frequency frequency) {

        if (rawRuns.isEmpty()) {
            return new RunPerformanceData(
                    calculatorKey, null, frequency.name(), days, 0L,
                    0, 0, 0, 0, 0,
                    Collections.emptyList(), null, null);
        }

        List<RunPerformanceData.RunDataPoint> dataPoints = rawRuns.stream()
                .map(run -> {
                    String slaStatus = classifySlaStatusForRun(run);
                    boolean isRunning = run.status() == RunStatus.RUNNING;
                    return new RunPerformanceData.RunDataPoint(
                            run.runId(),
                            run.reportingDate(),
                            run.startTime(),
                            isRunning ? null : run.endTime(),
                            isRunning ? null : run.durationMs(),
                            run.status().name(),
                            run.slaBand() != null ? run.slaBand().name() : null,
                            slaStatus,
                            null,
                            run.estimatedStartTime(),
                            run.slaTime(),
                            run.runNumber(),
                            run.expectedDurationMs()
                    );
                })
                .toList();

        return buildRunPerformanceDataEnvelope(calculatorKey, rawRuns, dataPoints, days, frequency);
    }

    private RunPerformanceData buildRunPerformanceDataEnvelope(
            String calculatorId,
            List<RunWithSlaStatus> rawRuns,
            List<RunPerformanceData.RunDataPoint> dataPoints,
            int days,
            Frequency frequency) {

        RunWithSlaStatus latestRaw = rawRuns.get(rawRuns.size() - 1);

        long totalDuration = 0;
        int completedCount = 0;
        int terminalRuns = 0;
        int runningRuns = 0;
        int slaMetCount = 0, lateCount = 0, veryLateCount = 0;

        for (RunPerformanceData.RunDataPoint dp : dataPoints) {
            boolean isRunning = RunStatus.RUNNING.name().equals(dp.status());
            if (isRunning) {
                runningRuns++;
            } else {
                terminalRuns++;
            }
            if (!isRunning && dp.durationMs() != null && dp.durationMs() > 0) {
                totalDuration += dp.durationMs();
                completedCount++;
            }
            if (!isRunning) {
                if ("ON_TIME".equals(dp.slaStatus())) slaMetCount++;
                else if ("LATE".equals(dp.slaStatus())) lateCount++;
                else if ("VERY_LATE".equals(dp.slaStatus())) veryLateCount++;
            }
        }

        long meanDuration = completedCount > 0 ? totalDuration / completedCount : 0;

        ReferenceLines refLines = resolveReferenceLines(latestRaw, frequency);

        return new RunPerformanceData(
                calculatorId,
                latestRaw.calculatorName(),
                frequency.name(),
                days,
                meanDuration,
                terminalRuns,
                runningRuns,
                slaMetCount,
                lateCount,
                veryLateCount,
                dataPoints,
                refLines.estimatedStartTime(),
                refLines.slaTime());
    }

    /**
     * Chart reference lines for the executions/performance-card view. Sourced from the cached
     * profile (stable "typical" start + buffered deadline) when it has enough samples; otherwise
     * falls back to the most recent run's stored values. Per-run RunDataPoints keep their own values.
     */
    private ReferenceLines resolveReferenceLines(
            RunWithSlaStatus latestRaw, Frequency frequency) {

        CalculatorProfile profile = calculatorProfileService.getProfile(
                latestRaw.calculatorName(), frequency);

        if (profile.hasSufficientSamples(slaModeProperties.getMinSampleSize())) {
            java.time.Instant estStart = com.company.observability.util.TimeUtils
                    .instantFromUtcMinuteOfDay(latestRaw.reportingDate(), profile.avgStartMinUtc());

            // The frozen slaTime is the authoritative deadline for every spec kind — use it directly.
            if (latestRaw.slaTime() != null) {
                return new ReferenceLines(estStart, latestRaw.slaTime());
            }

            // No frozen deadline (e.g. ungraded run): synthesize from the buffered profile average.
            long bufferedMs = Math.round(profile.avgDurationMs() * (1 + slaProperties.getThresholdPercent() / 100.0))
                    + slaModeProperties.lateBandMs();
            return new ReferenceLines(estStart, estStart.plusMillis(bufferedMs));
        }
        return new ReferenceLines(latestRaw.estimatedStartTime(), latestRaw.slaTime());
    }

    private record ReferenceLines(java.time.Instant estimatedStartTime, java.time.Instant slaTime) {}

    private String classifySlaStatusForRun(RunWithSlaStatus run) {
        if (run.status() == RunStatus.RUNNING || run.slaBand() == null) return SlaBand.ON_TIME.name();
        return run.slaBand().name(); // ON_TIME / LATE / VERY_LATE
    }

    // ================================================================
    // SLA classification helpers
    // ================================================================

    /**
     * Per-day classification for trends/summary (GREEN / AMBER / RED).
     * worstBand is one of: FAILED, VERY_LATE, LATE, ON_TIME (from findWorstDayHealthByDay).
     */
    private String classifyDay(DailyAggregate agg, String worstBand) {
        if (worstBand == null || agg.slaBreaches() == 0) {
            return SlaStatus.GREEN.name();
        }
        return mapBandToTrafficLight(worstBand);
    }

    private SlaCoreData getSlaCoreData(String calculatorId, int days) {
        SlaCoreData cached = cacheService.getFromCache(
                CACHE_SLA_CORE, calculatorId, days, SlaCoreData.class
        );
        if (cached != null) {
            return cached;
        }

        List<DailyAggregate> aggregates = dailyAggregateRepository
                .findRecentAggregates(calculatorId, days);
        Map<String, Integer> byBand = slaBreachEventRepository
                .countByBand(calculatorId, days);
        Map<String, Integer> byType = slaBreachEventRepository
                .countByType(calculatorId, days);
        Map<LocalDate, String> worstBandByDay = slaBreachEventRepository
                .findWorstDayHealthByDay(calculatorId, days);

        int totalBreaches = byBand.values().stream()
                .mapToInt(Integer::intValue)
                .sum();

        SlaCoreData coreData = SlaCoreData.builder()
                .aggregates(aggregates)
                .breachesByBand(byBand)
                .breachesByType(byType)
                .worstBandByDay(worstBandByDay)
                .totalBreaches(totalBreaches)
                .build();

        cacheService.putInCache(CACHE_SLA_CORE, calculatorId, days, coreData);
        return coreData;
    }

    private String mapBandToTrafficLight(String band) {
        if (band == null) return SlaStatus.AMBER.name();
        return switch (band) {
            case "FAILED", "VERY_LATE" -> SlaStatus.RED.name();
            default -> SlaStatus.AMBER.name(); // LATE
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
        private Map<String, Integer> breachesByBand;
        private Map<String, Integer> breachesByType;
        private Map<LocalDate, String> worstBandByDay;
        private int totalBreaches;
    }
}
