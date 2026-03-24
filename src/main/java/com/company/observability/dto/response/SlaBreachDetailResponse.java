package com.company.observability.dto.response;

import java.time.Instant;

public record SlaBreachDetailResponse(
        long breachId,
        String runId,
        String calculatorId,
        String calculatorName,
        String breachType,
        String severity,
        String slaStatus,
        Long expectedValue,
        Long actualValue,
        Instant createdAt
) {}
