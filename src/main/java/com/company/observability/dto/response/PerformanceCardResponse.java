package com.company.observability.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PerformanceCardResponse(
        String calculatorId,
        String calculatorName,
        List<ScheduleEntry> schedule,
        int periodDays,
        long meanDurationMs,
        SlaSummaryPct slaSummary,
        List<RunBar> runs,
        ReferenceLines referenceLines
) {
    public record ScheduleEntry(
            String runKey,
            String estimatedStartTime,
            String sla
    ) {}

    public record SlaSummaryPct(
            int totalRuns,
            int slaMetCount,
            int lateCount,
            int veryLateCount,
            double veryLatePct
    ) {}

    public record RunBar(
            String runId,
            LocalDate reportingDate,
            Instant startTime,
            Instant endTime,
            long durationMs,
            String slaStatus,
            List<String> subRunIds   // non-null only when this bar represents a collapsed split group
    ) {}

    public record ReferenceLines(
            double slaStartHourCet,
            double slaEndHourCet
    ) {}
}
