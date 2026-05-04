package com.company.observability.dto.response;

import java.time.LocalDate;
import java.util.List;

public record TrendAnalyticsResponse(
        String calculatorId,
        int periodDays,
        List<TrendDataPoint> trends
) {
    public record TrendDataPoint(
            LocalDate date,
            long avgDurationMs,
            int totalRuns,
            int successRuns,
            int slaBreaches,
            int avgStartMinUtc,
            int avgEndMinUtc,
            String slaStatus
    ) {}
}
