package com.company.observability.domain;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * GT Enhancement: Daily aggregated metrics per calculator
 * Pre-computed for fast dashboard queries
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyAggregate implements Serializable {
    private static final long serialVersionUID = 1L;

    private String calculatorId;
    private String tenantId;
    private LocalDate dayCet;
    private Integer totalRuns;
    private Integer successRuns;
    private Integer slaBreaches;
    private Long avgDurationMs;
    private Integer avgStartMinCet;  // Minutes since midnight CET (0-1439)
    private Integer avgEndMinCet;    // Minutes since midnight CET (0-1439)
    private Instant computedAt;
}