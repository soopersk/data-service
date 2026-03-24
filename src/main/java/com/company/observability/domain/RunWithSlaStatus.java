package com.company.observability.domain;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.Severity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Lightweight projection for performance card: calculator_runs LEFT JOIN sla_breach_events.
 * Severity is nullable (LEFT JOIN — null when no breach exists).
 */
public record RunWithSlaStatus(
        String runId,
        String calculatorId,
        String calculatorName,
        LocalDate reportingDate,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        BigDecimal startHourCet,
        BigDecimal endHourCet,
        Instant slaTime,
        Instant estimatedStartTime,
        CalculatorFrequency frequency,
        RunStatus status,
        Boolean slaBreached,
        String slaBreachReason,
        Severity severity
) {}
