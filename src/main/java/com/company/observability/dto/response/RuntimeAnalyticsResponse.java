package com.company.observability.dto.response;

import java.time.LocalDate;
import java.util.List;

public record RuntimeAnalyticsResponse(
        String calculatorId,
        int periodDays,
        String frequency,
        long avgDurationMs,
        String avgDurationFormatted,
        long minDurationMs,
        long maxDurationMs,
        int totalRuns,
        double successRate,
        List<DailyDataPoint> dataPoints
) {
    public record DailyDataPoint(
            LocalDate date,
            long avgDurationMs,
            int totalRuns,
            int successRuns
    ) {}
}
