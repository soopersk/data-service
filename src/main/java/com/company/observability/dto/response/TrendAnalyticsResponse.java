package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendAnalyticsResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private String calculatorId;
    private int periodDays;
    private List<TrendDataPoint> trends;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendDataPoint implements Serializable {
        private static final long serialVersionUID = 1L;

        private LocalDate date;
        private long avgDurationMs;
        private int totalRuns;
        private int successRuns;
        private int slaBreaches;
        private int avgStartMinCet;
        private int avgEndMinCet;
        private String slaStatus; // GREEN, AMBER, RED
    }
}
