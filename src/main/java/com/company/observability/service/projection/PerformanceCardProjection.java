package com.company.observability.service.projection;

import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.PerformanceCardResponse;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.service.AnalyticsService;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Maps {@link AnalyticsService} domain data to the pre-formatted
 * {@link PerformanceCardResponse} used by the performance-card endpoint.
 */
@Service
@RequiredArgsConstructor
public class PerformanceCardProjection {

    private final AnalyticsService analyticsService;

    public PerformanceCardResponse getPerformanceCard(
            String calculatorId, int days, Frequency frequency) {

        RunPerformanceData data = analyticsService
                .getRunPerformanceData(calculatorId, days, frequency);

        return toPerformanceCard(data);
    }

    private PerformanceCardResponse toPerformanceCard(RunPerformanceData data) {
        if (data.runs().isEmpty()) {
            return new PerformanceCardResponse(
                    data.calculatorId(), null, Collections.emptyList(), data.periodDays(), 0L,
                    new PerformanceCardResponse.SlaSummaryPct(0, 0, 0, 0, 0.0),
                    Collections.emptyList(), null);
        }

        List<PerformanceCardResponse.ScheduleEntry> schedule = buildSchedule(data.runs());

        PerformanceCardResponse.SlaSummaryPct slaSummary =
                new PerformanceCardResponse.SlaSummaryPct(
                        data.totalRuns(),
                        data.slaMetCount(),
                        data.lateCount(),
                        data.veryLateCount(),
                        pct(data.veryLateCount(), data.totalRuns()));

        List<PerformanceCardResponse.RunBar> runBars = data.runs().stream()
                .map(this::toRunBar)
                .toList();

        PerformanceCardResponse.ReferenceLines refLines = data.estimatedStartTime() != null
                ? new PerformanceCardResponse.ReferenceLines(
                        TimeUtils.calculateCetHour(data.estimatedStartTime()).doubleValue(),
                        TimeUtils.calculateCetHour(data.slaTime()).doubleValue())
                : null;

        return new PerformanceCardResponse(
                data.calculatorId(),
                data.calculatorName(),
                schedule,
                data.periodDays(),
                data.meanDurationMs(),
                slaSummary,
                runBars,
                refLines);
    }

    private List<PerformanceCardResponse.ScheduleEntry> buildSchedule(
            List<RunPerformanceData.RunDataPoint> runs) {

        record SlotKey(String startCet, String slaCet) {}

        LinkedHashSet<SlotKey> seen = new LinkedHashSet<>();
        for (RunPerformanceData.RunDataPoint run : runs) {
            if (run.estimatedStartTime() == null || run.slaTime() == null) continue;
            String startCet = TimeUtils.formatCetHour(TimeUtils.calculateCetHour(run.estimatedStartTime()));
            String slaCet   = TimeUtils.formatCetHour(TimeUtils.calculateCetHour(run.slaTime()));
            seen.add(new SlotKey(startCet, slaCet));
        }

        List<SlotKey> sorted = seen.stream()
                .sorted(Comparator.comparing(SlotKey::startCet))
                .toList();

        List<PerformanceCardResponse.ScheduleEntry> entries = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            SlotKey slot = sorted.get(i);
            entries.add(new PerformanceCardResponse.ScheduleEntry(
                    "run" + (i + 1), slot.startCet(), slot.slaCet()));
        }
        return entries;
    }

    private PerformanceCardResponse.RunBar toRunBar(RunPerformanceData.RunDataPoint run) {
        return new PerformanceCardResponse.RunBar(
                run.runId(),
                run.reportingDate(),
                run.startTime(),
                run.endTime(),
                run.durationMs() != null ? run.durationMs() : 0,
                run.slaStatus(),
                run.subRunIds());
    }

    private double pct(int count, int total) {
        if (total == 0) return 0.0;
        return Math.round((double) count / total * 1000.0) / 10.0;
    }
}
