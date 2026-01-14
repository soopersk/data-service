package com.company.observability.domain;

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
public class Calculator {
    private String calculatorId;
    private String name;
    private String description;
    private String frequency; // DAILY or MONTHLY
    private Long slaTargetDurationMs;
    private BigDecimal slaTargetEndHourCet;
    private String ownerTeam;
    private Boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}