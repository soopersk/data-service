package com.company.observability.dto.request;

import com.company.observability.domain.enums.CalculatorFrequency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

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

    @NotNull(message = "Frequency is required (DAILY or MONTHLY)")
    private CalculatorFrequency frequency;

    @NotNull(message = "Reporting date is required")
    @Schema(example = "2026-02-06")
    private LocalDate reportingDate;

    @NotNull(message = "Start time is required")
    @Schema(example = "2026-02-06T23:22:32Z")
    private Instant startTime;

    @NotNull(message = "SLA time (CET) is required")
    @Schema(type = "string", example = "06:15:00",
            description = "Target completion time in CET (time-of-day). Used for DAILY runs only.")
    private LocalTime slaTimeCet;

    // Optional fields
    private Long expectedDurationMs;
    private LocalTime estimatedStartTimeCet;

    // Optional — promoted from run_parameters for query efficiency.
    // Top-level fields take precedence; fall back to runParameters map for backward compat.
    private String runNumber;
    private String runType;
    private String region;

    private Map<String, Object> runParameters;
    private Map<String, Object> additionalAttributes;
}
