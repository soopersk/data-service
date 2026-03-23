package com.company.observability.controller;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.service.RunQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

        meterRegistry.counter("api.calculators.status.requests",
                "frequency", freq.name(),
                "bypass_cache", String.valueOf(bypassCache)
        ).increment();

        CalculatorStatusResponse response = queryService.getCalculatorStatus(
                calculatorId, tenantId, freq, historyLimit, bypassCache);

        CacheControl cacheControl = bypassCache
                ? CacheControl.noCache()
                : CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .body(response);
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

        log.debug("Batch status query for {} calculators (frequency={}, allowStale={})",
                calculatorIds.size(), freq, allowStale);

        meterRegistry.counter("api.calculators.batch.requests",
                "frequency", freq.name(),
                "allow_stale", String.valueOf(allowStale)
        ).increment();

        List<CalculatorStatusResponse> response = queryService.getBatchCalculatorStatus(
                calculatorIds, tenantId, freq, historyLimit, allowStale);

        CacheControl cacheControl = allowStale
                ? CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate()
                : CacheControl.noCache();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .body(response);
    }
}
