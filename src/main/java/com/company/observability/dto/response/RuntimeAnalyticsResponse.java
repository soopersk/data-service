package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeAnalyticsResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private String calculatorId;
    private int periodDays;
    private String frequency;
    private long avgDurationMs;
    private String avgDurationFormatted;
    private long minDurationMs;
    private long maxDurationMs;
    private int totalRuns;
    private double successRate;
    private List<DailyDataPoint> dataPoints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyDataPoint implements Serializable {
        private static final long serialVersionUID = 1L;

        private LocalDate date;
        private long avgDurationMs;
        private int totalRuns;
        private int successRuns;
    }
}
