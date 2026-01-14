package com.company.observability.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunDetailResponse {
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
    private Long slaDurationMs;
    private BigDecimal slaEndHourCet;
    private Boolean slaBreached;
    private String slaBreachReason;
    private Map<String, Object> runParameters;
    private Instant createdAt;
    private Instant updatedAt;
}