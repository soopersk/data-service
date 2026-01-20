package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunStatusInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String runId;
    private String status; // RUNNING, COMPLETED, FAILED, TIMEOUT, NOT_STARTED
    private Instant start;
    private Instant end;
    private Instant estimatedStart;
    private Instant estimatedEnd;
    private Instant sla; // Absolute SLA deadline time

    // Additional useful attributes
    private Long durationMs;
    private String durationFormatted;
    private Boolean slaBreached;
    private String slaBreachReason;

}