package com.company.observability.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request to start a calculator run with reporting_date
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartRunRequest {
    @NotBlank(message = "Run ID is required")
    private String runId;

    @NotBlank(message = "Calculator ID is required")
    private String calculatorId;

    @NotBlank(message = "Calculator name is required")
    private String calculatorName;

    @NotBlank(message = "Frequency is required (DAILY or MONTHLY)")
    private String frequency;

    @NotNull(message = "Reporting date is required")
    private LocalDate reportingDate;

    @NotNull(message = "Start time is required")
    private Instant startTime;

    @NotNull(message = "SLA time (CET) is required")
    private LocalTime slaTimeCet;  // e.g., "06:15:00"

    // Optional fields
    private Long expectedDurationMs;
    private LocalTime estimatedStartTimeCet;
    private String runParameters;
}