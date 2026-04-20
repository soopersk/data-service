package com.company.observability.controller;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.CalculatorDashboardResponse;
import com.company.observability.dto.response.PerformanceCardResponse;
import com.company.observability.dto.response.RegionalBatchStatusResponse;
import com.company.observability.service.projection.DashboardProjection;
import com.company.observability.service.projection.PerformanceCardProjection;
import com.company.observability.service.projection.RegionalBatchProjection;
import com.company.observability.util.ObservabilityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/analytics/projections")
@Tag(name = "Analytics Projections", description = "Pre-formatted analytical views with CET times, duration strings, and chart coordinates")
@RequiredArgsConstructor
@Slf4j
public class ProjectionController {

    private final PerformanceCardProjection performanceCardProjection;
    private final RegionalBatchProjection regionalBatchProjection;
    private final DashboardProjection dashboardProjection;
    private final MeterRegistry meterRegistry;

    @GetMapping("/calculators/{calculatorId}/performance-card")
    @Operation(
            summary = "Performance card projection",
            description = "Returns pre-formatted performance card data with CET times, " +
                    "duration strings, SLA percentages, and chart coordinates. " +
                    "For raw domain data, use GET /api/v1/analytics/calculators/{calculatorId}/run-performance."
    )
    public ResponseEntity<PerformanceCardResponse> getPerformanceCard(
            @PathVariable String calculatorId,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Parameter(description = "Lookback period in days (1-365)")
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @Parameter(description = "Frequency: DAILY or MONTHLY")
            @RequestParam(defaultValue = "DAILY") String frequency) {

        CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PerformanceCardResponse response = performanceCardProjection
                    .getPerformanceCard(calculatorId, tenantId, days, freq);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/projections/performance-card"));
        }
    }

    @GetMapping("/calculator-dashboard")
    @Operation(
            summary = "Unified calculator dashboard",
            description = "Returns the full state of all 5 accordion sections (Regional, Portfolio, " +
                    "Group Portfolio, Risk Governed, Consolidation) for a given reporting date, " +
                    "frequency, and run number. The UI polls this every 60 seconds."
    )
    public ResponseEntity<CalculatorDashboardResponse> getCalculatorDashboard(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Parameter(description = "Reporting date (ISO format, e.g. 2026-04-17)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate,
            @Parameter(description = "Frequency: DAILY or MONTHLY")
            @RequestParam(defaultValue = "DAILY") String frequency,
            @Parameter(description = "Run number: 1 or 2")
            @RequestParam(defaultValue = "1") @Min(1) @Max(2) int runNumber) {

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CalculatorDashboardResponse response = dashboardProjection
                    .getCalculatorDashboard(tenantId, reportingDate, frequency, runNumber);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/projections/calculator-dashboard"));
        }
    }

    @Deprecated
    @GetMapping("/regional-batch-status")
    @Operation(
            summary = "Regional batch status projection (deprecated)",
            description = "Deprecated — use GET /api/v1/analytics/projections/calculator-dashboard instead. " +
                    "Returns the status of all regional batch runs for a given reporting date."
    )
    public ResponseEntity<RegionalBatchStatusResponse> getRegionalBatchStatus(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Parameter(description = "Reporting date (ISO format, e.g. 2026-04-17)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate) {

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RegionalBatchStatusResponse response = regionalBatchProjection
                    .getRegionalBatchStatus(tenantId, reportingDate);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                    .header("Deprecation", "true")
                    .header("Sunset", "Sun, 31 May 2026 00:00:00 GMT")
                    .header("Link", "</api/v1/analytics/projections/calculator-dashboard>; rel=\"successor-version\"")
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/projections/regional-batch-status"));
        }
    }
}
