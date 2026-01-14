package com.company.observability.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorRun {
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private String frequency;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;
    private String status;
    private Long slaDurationMs;
    private BigDecimal slaEndHourCet;
    private Boolean slaBreached;
    private String slaBreachReason;
    private String runParameters; // JSON string
    private Instant createdAt;
    private Instant updatedAt;
}