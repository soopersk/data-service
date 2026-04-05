package com.company.observability.controller;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.PerformanceCardResponse;
import com.company.observability.service.ProjectionService;
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
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/analytics/projections")
@Tag(name = "Analytics Projections", description = "Pre-formatted analytical views with CET times, duration strings, and chart coordinates")
@RequiredArgsConstructor
@Slf4j
public class ProjectionController {

    private final ProjectionService projectionService;
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
            PerformanceCardResponse response = projectionService
                    .getPerformanceCard(calculatorId, tenantId, days, freq);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/projections/performance-card"));
        }
    }
}
