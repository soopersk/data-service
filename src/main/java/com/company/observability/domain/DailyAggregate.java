package com.company.observability.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily aggregated metrics per calculator. Pre-computed for fast dashboard queries.
 */
public record DailyAggregate(
        String calculatorId,
        String tenantId,
        LocalDate dayCet,
        Integer totalRuns,
        Integer successRuns,
        Integer slaBreaches,
        Long avgDurationMs,
        Integer avgStartMinCet,
        Integer avgEndMinCet,
        Instant computedAt
) {}
