package com.company.observability.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RunStatusInfo(
        String runId,
        @Schema(description = "Run status", allowableValues = {"RUNNING", "SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"})
        String status,
        Instant start,
        Instant end,
        Instant estimatedStart,
        Instant estimatedEnd,
        Instant sla,
        Long durationMs,
        String durationFormatted,
        Boolean slaBreached,
        String slaBreachReason
) {}
