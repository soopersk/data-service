package com.company.observability.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunSummaryResponse {
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private String durationFormatted;
    private BigDecimal startHourCet;
    private BigDecimal endHourCet;
    private String startTimeCetFormatted;
    private String endTimeCetFormatted;
    private String status;
    private Boolean slaBreached;
    private String slaBreachReason;
    private String frequency;
}