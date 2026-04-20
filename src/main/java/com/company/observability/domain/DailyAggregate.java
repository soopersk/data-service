package com.company.observability.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily aggregated metrics per calculator. Pre-computed for fast dashboard queries.
 * Stores sums to avoid the running-average race condition under concurrent upserts (TD-3 fix).
 * Averages are computed at read time via the helper methods below.
 */
public record DailyAggregate(
        String calculatorId,
        String tenantId,
        LocalDate dayCet,
        int totalRuns,
        int successRuns,
        int slaBreaches,
        long sumDurationMs,
        long sumStartMinCet,
        long sumEndMinCet,
        Instant computedAt
) {
    /** Average run duration in ms. Returns 0 when no runs recorded. */
    public long avgDurationMs() {
        return totalRuns > 0 ? sumDurationMs / totalRuns : 0;
    }

    /** Average start minute (CET) across all runs. Returns 0 when no runs recorded. */
    public int avgStartMinCet() {
        return totalRuns > 0 ? (int) (sumStartMinCet / totalRuns) : 0;
    }

    /** Average end minute (CET) across all runs. Returns 0 when no runs recorded. */
    public int avgEndMinCet() {
        return totalRuns > 0 ? (int) (sumEndMinCet / totalRuns) : 0;
    }
}
