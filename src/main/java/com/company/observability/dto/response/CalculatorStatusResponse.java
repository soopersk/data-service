package com.company.observability.dto.response;

import java.time.Instant;
import java.util.List;

public record CalculatorStatusResponse(
        String calculatorName,
        Instant lastRefreshed,
        RunStatusInfo current,
        List<RunStatusInfo> history
) {}
