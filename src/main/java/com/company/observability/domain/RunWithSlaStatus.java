package com.company.observability.domain;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Lightweight projection for performance card: calculator_runs LEFT JOIN sla_breach_events
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunWithSlaStatus {
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private LocalDate reportingDate;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;
    private Instant slaTime;
    private Instant estimatedStartTime;
    private String frequency;
    private String status;
    private Boolean slaBreached;
    private String slaBreachReason;
    private String severity; // from sla_breach_events (nullable)
}
