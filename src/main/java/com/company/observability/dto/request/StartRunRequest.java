package com.company.observability.dto.request;

import com.company.observability.domain.enums.Frequency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
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
    private Frequency frequency;

    @NotNull(message = "Reporting date is required")
    @Schema(example = "2026-02-06")
    private LocalDate reportingDate;

    @NotNull(message = "Start time is required")
    @Schema(example = "2026-02-06T23:22:32Z")
    private Instant startTime;

    @Schema(description = "Phase-1 (CLOCK_TIME mode): UTC clock time HH:mm at which this run must complete " +
            "(e.g. \"22:00\"). Rolled forward +1 day when the clock time is at or before startTime (overnight SLAs). " +
            "Phase-2 (DURATION mode): ISO-8601 duration (e.g. \"PT2H30M\"). " +
            "The persisted/response slaTime is always the derived absolute deadline instant (UTC).")
    private String slaTime;

    // Optional fields
    private Long expectedDurationMs;

    // Optional. If Airflow supplies these, they win; otherwise they are derived from the
    // calculator's cached historical profile, then from start + expectedDurationMs.
    private Instant estimatedStartTime;
    private Instant estimatedEndTime;

    // Optional — promoted from run_parameters for query efficiency.
    // Top-level fields take precedence; fall back to runParameters map for backward compat.
    private String runNumber;
    private String runType;
    private String region;

    // Optional — set the same value on every physical split that belongs to one logical run.
    // Null means this is a standalone run (not a split).
    private String correlationId;

    private Map<String, Object> runParameters;
    private Map<String, Object> additionalAttributes;
}
