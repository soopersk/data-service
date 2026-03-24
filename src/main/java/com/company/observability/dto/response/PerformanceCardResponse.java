package com.company.observability.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PerformanceCardResponse(
        String calculatorId,
        String calculatorName,
        ScheduleInfo schedule,
        int periodDays,
        long meanDurationMs,
        String meanDurationFormatted,
        SlaSummaryPct slaSummary,
        List<RunBar> runs,
        ReferenceLines referenceLines
) {
    public record ScheduleInfo(
            String estimatedStartTimeCet,
            String frequency
    ) {}

    public record SlaSummaryPct(
            int totalRuns,
            int slaMetCount,
            double slaMetPct,
            int lateCount,
            double latePct,
            int veryLateCount,
            double veryLatePct
    ) {}

    public record RunBar(
            String runId,
            LocalDate reportingDate,
            String dateFormatted,
            BigDecimal startHourCet,
            BigDecimal endHourCet,
            String startTimeCet,
            String endTimeCet,
            long durationMs,
            String durationFormatted,
            String slaStatus
    ) {}

    public record ReferenceLines(
            BigDecimal slaStartHourCet,
            BigDecimal slaEndHourCet
    ) {}
}
