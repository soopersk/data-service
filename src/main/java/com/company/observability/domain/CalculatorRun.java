package com.company.observability.domain;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

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

    @Builder.Default
    private CalculatorFrequency frequency = CalculatorFrequency.DAILY;

    // Partition key - critical for query performance
    private LocalDate reportingDate;

    // Timing information
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;

    // CET time conversions for display
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;

    // Default to RUNNING
    @Builder.Default
    private RunStatus status = RunStatus.RUNNING;

    // SLA tracking
    private Instant slaTime;
    private Long expectedDurationMs;
    private Instant estimatedStartTime;
    private Instant estimatedEndTime;

    private Boolean slaBreached;
    private String slaBreachReason;

    // Metadata
    private Map<String, Object> runParameters;
    private Map<String, Object> additionalAttributes;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Helper to determine if this is a DAILY run
     */
    public boolean isDaily() {
        return frequency == CalculatorFrequency.DAILY;
    }

    /**
     * Helper to determine if this is a MONTHLY run
     */
    public boolean isMonthly() {
        return frequency == CalculatorFrequency.MONTHLY;
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
