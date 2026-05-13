package com.company.observability.controller;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.service.CalculatorStateService;
import com.company.observability.service.RunQueryService;
import com.company.observability.util.ObservabilityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Query controller with enum support
 */
@RestController
@RequestMapping("/api/v1/calculators")
@Tag(name = "Calculator Status", description = "Query calculator runtime status and history")
@RequiredArgsConstructor
@Slf4j
public class RunQueryController {

    private final RunQueryService queryService;
    private final CalculatorStateService calculatorStateService;
    private final MeterRegistry meterRegistry;

    @GetMapping("/{calculatorId}/status")
    @Operation(
            summary = "Get calculator status with current run and history",
            description = "Returns last N runs based on frequency (DAILY: 2-3 days, MONTHLY: end-of-month). " +
                         "Set bypassCache=true to force fresh data from database."
    )
    public ResponseEntity<CalculatorStatusResponse> getCalculatorStatus(
            @PathVariable String calculatorId,
            @RequestHeader("X-Tenant-Id") String tenantId,

            @Parameter(description = "Frequency: DAILY, MONTHLY, D, or M (case-insensitive)")
            @RequestParam String frequency,

            @Parameter(description = "Number of historical runs to return (1-100)")
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int historyLimit,

            @Parameter(description = "Bypass all caches and force database query")
            @RequestParam(defaultValue = "false") boolean bypassCache) {

        // Parse frequency using enum's built-in parser
        CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CalculatorStatusResponse response = queryService.getCalculatorStatus(
                    calculatorId, tenantId, freq, historyLimit, bypassCache);

            CacheControl cacheControl = bypassCache
                    ? CacheControl.noCache()
                    : CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate();

            return ResponseEntity.ok()
                    .cacheControl(cacheControl)
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_QUERY_DURATION,
                    "endpoint", "/api/v1/calculators/{calculatorId}/status",
                    "frequency", freq.name()));
        }
    }

    @PostMapping("/batch/status")
    @Operation(
            summary = "Get status for multiple calculators",
            description = "Optimized batch query with partition pruning. " +
                         "Set allowStale=false to force fresh database queries for all calculators."
    )
    public ResponseEntity<List<CalculatorStatusResponse>> getBatchCalculatorStatus(
            @RequestBody @NotEmpty @Size(max = 100) List<String> calculatorIds,
            @RequestHeader("X-Tenant-Id") String tenantId,

            @Parameter(description = "Frequency: DAILY, MONTHLY, D, or M")
            @RequestParam String frequency,

            @Parameter(description = "Number of historical runs per calculator (1-50)")
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int historyLimit,

            @Parameter(description = "Allow stale cached data. Set false to force fresh queries.")
            @RequestParam(defaultValue = "true") boolean allowStale) {

        // Parse frequency using enum
        CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);

        log.debug("event=query.batch outcome=accepted count={} frequency={} allowStale={}",
                calculatorIds.size(), freq, allowStale);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<CalculatorStatusResponse> response = queryService.getBatchCalculatorStatus(
                    calculatorIds, tenantId, freq, historyLimit, allowStale);

            CacheControl cacheControl = allowStale
                    ? CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate()
                    : CacheControl.noCache();

            return ResponseEntity.ok()
                    .cacheControl(cacheControl)
                    .body(response);
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_QUERY_DURATION,
                    "endpoint", "/api/v1/calculators/batch/status",
                    "frequency", freq.name()));
        }
    }

    @GetMapping("/batch/runs")
    @Operation(
            summary = "Batch calculator runs by reporting date",
            description = "Returns all dimensional run instances per logical calculator for a specific reporting date. " +
                    "Regional calculators return one RunEntry per region; typed calculators return one per runType. " +
                    "Empty runs list = no run found. isRerun=true = a re-trigger was fired for that dimension."
    )
    public ResponseEntity<CalculatorBatchRunsResponse> getBatchRuns(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam("reporting_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate,
            @RequestParam(defaultValue = "DAILY") String frequency,
            @RequestParam(value = "run_number", defaultValue = "1") @Min(1) @Max(2) int runNumber,
            @RequestParam @NotBlank String keys) {

        List<String> calculatorIds = Arrays.stream(keys.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (calculatorIds.isEmpty()) {
            throw new IllegalArgumentException("keys must contain at least one non-blank calculator ID");
        }

        CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Map<String, CalculatorBatchRunsResponse.CalculatorEntry> calculators =
                    calculatorStateService.getState(tenantId, reportingDate, freq, runNumber, calculatorIds);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                    .body(new CalculatorBatchRunsResponse(
                            reportingDate, freq.name(), runNumber, Instant.now(), calculators));
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/calculators/batch/runs"));
        }
    }
}
