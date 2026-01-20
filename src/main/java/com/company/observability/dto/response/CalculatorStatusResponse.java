package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * New response format matching the requirement
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorStatusResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private String calculatorName;
    private Instant lastRefreshed;
    private RunStatusInfo current;
    private List<RunStatusInfo> history;
}