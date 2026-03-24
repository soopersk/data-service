package com.company.observability.controller;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.*;
import com.company.observability.service.AnalyticsService;
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
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Calculator analytics, trends, and SLA reporting")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final MeterRegistry meterRegistry;

    @GetMapping("/calculators/{calculatorId}/runtime")
    @Operation(
            summary = "Runtime analytics for a calculator",
            description = "Returns weighted average duration, min/max, success rate, " +
                    "and daily data points over the specified period."
    )
    public ResponseEntity<RuntimeAnalyticsResponse> getRuntimeAnalytics(
            @PathVariable String calculatorId,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Parameter(description = "Lookback period in days (1-365)")
            @RequestParam @Min(1) @Max(365) int days,
            @Parameter(description = "Frequency: DAILY or MONTHLY")
            @RequestParam(defaultValue = "DAILY") String frequency) {

        CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RuntimeAnalyticsResponse response = analyticsService
                    .getRuntimeAnalytics(calculatorId, tenantId, days, freq);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/runtime"));
        }
    }

    @GetMapping("/calculators/{calculatorId}/sla-summary")
    @Operation(
            summary = "SLA breach summary",
            description = "Returns GREEN/AMBER/RED day counts, breaches by severity and type."
    )
    public ResponseEntity<SlaSummaryResponse> getSlaSummary(
            @PathVariable String calculatorId,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Parameter(description = "Lookback period in days (1-365)")
            @RequestParam @Min(1) @Max(365) int days) {

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SlaSummaryResponse response = analyticsService
                    .getSlaSummary(calculatorId, tenantId, days);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/sla-summary"));
        }
    }

    @GetMapping("/calculators/{calculatorId}/trends")
    @Operation(
            summary = "Trend analysis",
            description = "Returns daily timeline with duration, run counts, " +
                    "success rate, and SLA status (GREEN/AMBER/RED) per day."
    )
    public ResponseEntity<TrendAnalyticsResponse> getTrends(
            @PathVariable String calculatorId,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Parameter(description = "Lookback period in days (1-365)")
            @RequestParam @Min(1) @Max(365) int days) {

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TrendAnalyticsResponse response = analyticsService
                    .getTrends(calculatorId, tenantId, days);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/trends"));
        }
    }

    @GetMapping("/calculators/{calculatorId}/sla-breaches")
    @Operation(
            summary = "SLA breach details (paginated)",
            description = "Returns paginated list of individual SLA breach events " +
                    "with optional severity filter. Prefer cursor-based pagination " +
                    "using nextCursor for deep traversal."
    )
    public ResponseEntity<PagedResponse<SlaBreachDetailResponse>> getSlaBreachDetails(
            @PathVariable String calculatorId,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Parameter(description = "Lookback period in days (1-365)")
            @RequestParam @Min(1) @Max(365) int days,
            @Parameter(description = "Filter by severity: LOW, MEDIUM, HIGH, CRITICAL")
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Opaque cursor from previous response nextCursor")
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PagedResponse<SlaBreachDetailResponse> response = analyticsService
                    .getSlaBreachDetails(calculatorId, tenantId, days, severity, page, size, cursor);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/sla-breaches"));
        }
    }

    @GetMapping("/calculators/{calculatorId}/performance-card")
    @Operation(
            summary = "Calculator performance card data",
            description = "Returns all data needed for the dashboard performance card: " +
                    "schedule, mean runtime, SLA summary percentages, individual run bars " +
                    "for chart rendering, and reference lines."
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
            PerformanceCardResponse response = analyticsService
                    .getPerformanceCard(calculatorId, tenantId, days, freq);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/performance-card"));
        }
    }
}
