package com.company.observability.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunResponse {
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String status;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private Boolean slaBreached;
    private String slaBreachReason;
}