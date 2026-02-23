package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaBreachDetailResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private long breachId;
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String breachType;
    private String severity;
    private String slaStatus; // AMBER or RED
    private Long expectedValue;
    private Long actualValue;
    private Instant createdAt;
}
