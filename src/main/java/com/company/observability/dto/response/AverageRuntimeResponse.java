package com.company.observability.dto.response;

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
public class AverageRuntimeResponse {
    private String calculatorId;
    private String frequency;
    private Integer lookbackDays;
    private Instant periodStart;
    private Instant periodEnd;
    private Integer totalRuns;
    private Integer successfulRuns;
    private Integer failedRuns;
    private Long avgDurationMs;
    private Long minDurationMs;
    private Long maxDurationMs;
    private String avgDurationFormatted;
    private BigDecimal avgStartHourCet;
    private BigDecimal avgEndHourCet;
    private String avgStartTimeCet;
    private String avgEndTimeCet;
    private Integer slaBreaches;
    private BigDecimal slaComplianceRate;
}