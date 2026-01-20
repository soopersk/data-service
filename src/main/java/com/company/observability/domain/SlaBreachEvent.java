package com.company.observability.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaBreachEvent {
    private Long breachId;
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private String breachType;
    private Long expectedValue;
    private Long actualValue;
    private String severity;
    private Boolean alerted;
    private Instant alertedAt;
    private String alertStatus;
    private Integer retryCount;
    private String lastError;
    private Instant createdAt;
}