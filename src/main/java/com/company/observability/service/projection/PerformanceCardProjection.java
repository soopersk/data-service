package com.company.observability.service.projection;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.PerformanceCardResponse;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.service.AnalyticsService;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Maps {@link AnalyticsService} domain data to the pre-formatted
 * {@link PerformanceCardResponse} used by the performance-card endpoint.
 */
@Service
@RequiredArgsConstructor
public class PerformanceCardProjection {

    private final AnalyticsService analyticsService;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH);

    public PerformanceCardResponse getPerformanceCard(
            String calculatorId, String tenantId, int days, CalculatorFrequency frequency) {

        RunPerformanceData data = analyticsService
                .getRunPerformanceData(calculatorId, tenantId, days, frequency);

        return toPerformanceCard(data);
    }

    private PerformanceCardResponse toPerformanceCard(RunPerformanceData data) {
        if (data.runs().isEmpty()) {
            return new PerformanceCardResponse(
                    data.calculatorId(), null, null, data.periodDays(), 0L, null,
                    new PerformanceCardResponse.SlaSummaryPct(0, 0, 0.0, 0, 0.0, 0, 0.0),
                    Collections.emptyList(), null);
        }

        BigDecimal slaStartHour = TimeUtils.calculateCetHour(data.estimatedStartTime());
        BigDecimal slaEndHour   = TimeUtils.calculateCetHour(data.slaTime());

        String startTimeCet = slaStartHour != null ? TimeUtils.formatCetHour(slaStartHour) : null;

        PerformanceCardResponse.SlaSummaryPct slaSummary =
                new PerformanceCardResponse.SlaSummaryPct(
                        data.totalRuns(),
                        data.slaMetCount(),   pct(data.slaMetCount(),   data.totalRuns()),
                        data.lateCount(),     pct(data.lateCount(),     data.totalRuns()),
                        data.veryLateCount(), pct(data.veryLateCount(), data.totalRuns()));

        List<PerformanceCardResponse.RunBar> runBars = data.runs().stream()
                .map(this::toRunBar)
                .toList();

        return new PerformanceCardResponse(
                data.calculatorId(),
                data.calculatorName(),
                new PerformanceCardResponse.ScheduleInfo(startTimeCet, data.frequency()),
                data.periodDays(),
                data.meanDurationMs(),
                TimeUtils.formatDuration(data.meanDurationMs()),
                slaSummary,
                runBars,
                new PerformanceCardResponse.ReferenceLines(slaStartHour, slaEndHour));
    }

    private PerformanceCardResponse.RunBar toRunBar(RunPerformanceData.RunDataPoint run) {
        String dateFormatted = run.reportingDate() != null
                ? run.reportingDate().format(DATE_FORMATTER) : null;

        BigDecimal startHour = TimeUtils.calculateCetHour(run.startTime());
        BigDecimal endHour   = TimeUtils.calculateCetHour(run.endTime());

        String startCet = startHour != null ? TimeUtils.formatCetHour(startHour) + " CET" : null;
        String endCet   = endHour   != null ? TimeUtils.formatCetHour(endHour)   + " CET" : null;

        return new PerformanceCardResponse.RunBar(
                run.runId(),
                run.reportingDate(),
                dateFormatted,
                startHour,
                endHour,
                startCet,
                endCet,
                run.durationMs() != null ? run.durationMs() : 0,
                TimeUtils.formatDuration(run.durationMs()),
                run.slaStatus());
    }

    private double pct(int count, int total) {
        if (total == 0) return 0.0;
        return Math.round((double) count / total * 1000.0) / 10.0;
    }
}
