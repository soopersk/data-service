package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceCardResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    // Card header
    private String calculatorId;
    private String calculatorName;
    private ScheduleInfo schedule;
    private int periodDays;
    private long meanDurationMs;
    private String meanDurationFormatted;

    // SLA stacked bar (percentages MUST sum to 100%)
    private SlaSummaryPct slaSummary;

    // Individual runs for bar chart (ordered chronologically: oldest first)
    private List<RunBar> runs;

    // Horizontal reference lines for chart
    private ReferenceLines referenceLines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        private String estimatedStartTimeCet; // "10:00"
        private String frequency;             // "DAILY" or "MONTHLY"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlaSummaryPct implements Serializable {
        private static final long serialVersionUID = 1L;

        private int totalRuns;
        private int slaMetCount;
        private double slaMetPct;      // dark green — "SLA met"
        private int lateCount;
        private double latePct;        // amber — "Late" (LOW/MEDIUM)
        private int veryLateCount;
        private double veryLatePct;    // red — "Very late" (HIGH/CRITICAL)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunBar implements Serializable {
        private static final long serialVersionUID = 1L;

        private String runId;
        private LocalDate reportingDate;
        private String dateFormatted;       // "Tue 27 Jan 2026"
        private BigDecimal startHourCet;    // Y-axis bar bottom
        private BigDecimal endHourCet;      // Y-axis bar top
        private String startTimeCet;        // "11:07 CET"
        private String endTimeCet;          // "13:14 CET"
        private long durationMs;
        private String durationFormatted;   // "2hrs 7mins"
        private String slaStatus;           // SLA_MET, LATE, VERY_LATE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferenceLines implements Serializable {
        private static final long serialVersionUID = 1L;

        private BigDecimal slaStartHourCet;  // circle markers
        private BigDecimal slaEndHourCet;    // diamond markers
    }
}
