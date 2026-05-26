package com.company.observability.dto.enums;

/**
 * API-facing traffic-light status for day-level SLA compliance.
 * Used in TrendDataPoint and SlaSummaryResponse.
 * Run-level classification uses {@link com.company.observability.domain.enums.SlaBand} directly.
 */
public enum SlaStatus {
    GREEN, AMBER, RED
}
