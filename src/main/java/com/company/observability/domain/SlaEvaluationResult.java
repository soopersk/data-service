package com.company.observability.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SlaEvaluationResult {
    private boolean breached;
    private String reason;
    private String severity;
}
