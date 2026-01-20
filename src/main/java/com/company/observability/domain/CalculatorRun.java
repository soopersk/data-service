package com.company.observability.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Calculator Run domain model with reporting_date for partition key
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorRun implements Serializable {
    private static final long serialVersionUID = 1L;

    // Primary key
    private String runId;

    // Calculator metadata
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private String frequency; // DAILY or MONTHLY

    // Partition key - critical for query performance
    private LocalDate reportingDate;

    // Timing information
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;

    // CET time conversions for display
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;

    // Status
    private String status; // RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED

    // SLA tracking
    private Instant slaTime;
    private Long expectedDurationMs;
    private Instant estimatedStartTime;
    private Instant estimatedEndTime;

    private Boolean slaBreached;
    private String slaBreachReason;

    // Metadata
    private String runParameters;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Helper to determine if this is a DAILY run
     */
    public boolean isDaily() {
        return "DAILY".equalsIgnoreCase(frequency);
    }

    /**
     * Helper to determine if this is a MONTHLY run
     */
    public boolean isMonthly() {
        return "MONTHLY".equalsIgnoreCase(frequency);
    }

    /**
     * Helper to check if this is an end-of-month reporting date
     */
    public boolean isEndOfMonth() {
        if (reportingDate == null) return false;
        LocalDate nextDay = reportingDate.plusDays(1);
        return nextDay.getMonth() != reportingDate.getMonth();
    }
}