package com.company.observability.dto.response;

import java.util.Map;

public record SlaSummaryResponse(
        String calculatorId,
        int periodDays,
        int totalBreaches,
        int greenDays,
        int amberDays,
        int redDays,
        Map<String, Integer> breachesBySeverity,
        Map<String, Integer> breachesByType
) {}
