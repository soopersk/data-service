package com.company.observability.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record CalculatorBatchRunsResponse(
        LocalDate reportingDate,
        String frequency,
        int runNumber,
        Instant generatedAt,
        Map<String, CalculatorEntry> calculators
) {
    public record CalculatorEntry(
            String calculatorId,
            String calculatorName,
            List<RunEntry> runs
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunEntry(
            String runId,
            String region,
            String runType,
            String status,
            String slaStatus,
            Instant startTime,
            Instant endTime,
            Instant estimatedStartTime,
            Instant estimatedEndTime,
            Instant sla,
            Long durationMs,
            Boolean slaBreached,
            String slaBreachReason,
            Long latenessMs,
            boolean isRerun
    ) {}
}
