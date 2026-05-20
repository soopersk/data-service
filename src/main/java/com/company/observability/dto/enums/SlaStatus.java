package com.company.observability.dto.enums;

/**
 * API-facing traffic-light status for SLA compliance.
 * Distinct from domain Severity — maps multiple severity levels to display categories.
 */
public enum SlaStatus {
    GREEN, AMBER, RED,          // day-level classification (TrendDataPoint, SlaSummary)
    ON_TIME, LATE, VERY_LATE    // run-level classification (batch/runs, executions)
}
