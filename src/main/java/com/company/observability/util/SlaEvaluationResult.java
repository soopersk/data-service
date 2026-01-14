package com.company.observability.util;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SlaEvaluationResult {
    private boolean breached;
    private String reason;
    private String severity;
}