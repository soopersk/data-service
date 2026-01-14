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
public class CalculatorStatistics {
    private Long statId;
    private String calculatorId;
    private String tenantId;
    private Integer periodDays;
    private Instant periodStart;
    private Instant periodEnd;
    private Integer totalRuns;
    private Integer successfulRuns;
    private Integer failedRuns;
    private Long avgDurationMs;
    private Long minDurationMs;
    private Long maxDurationMs;
    private BigDecimal avgStartHourCet;
    private BigDecimal avgEndHourCet;
    private Integer slaBreaches;
    private Instant computedAt;
}