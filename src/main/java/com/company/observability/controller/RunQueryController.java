package com.company.observability.controller;

import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.security.TenantContext;
import com.company.observability.service.RunQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/calculators")
@Tag(name = "Calculator Status", description = "Query calculator runtime status and history")
@RequiredArgsConstructor
@Slf4j
@SecurityRequirement(name = "bearer-jwt")
public class RunQueryController {

    private final RunQueryService queryService;
    private final TenantContext tenantContext;
    private final MeterRegistry meterRegistry;

    /**
     * Get calculator status with partition-aware queries
     */
    @GetMapping("/{calculatorId}/status")
    @Operation(
            summary = "Get calculator status with current run and history",
            description = "Returns last N runs based on frequency (DAILY: 2-3 days, MONTHLY: end-of-month)"
    )
    @PreAuthorize("hasAnyRole('UI_READER', 'AIRFLOW')")
    public ResponseEntity<CalculatorStatusResponse> getCalculatorStatus(
            @PathVariable String calculatorId,
            @RequestParam @NotBlank String frequency,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int historyLimit) {

        String tenantId = tenantContext.getCurrentTenantId();

        meterRegistry.counter("api.calculators.status.requests",
                "calculator", calculatorId,
                "frequency", frequency
        ).increment();

        CalculatorStatusResponse response = queryService.getCalculatorStatus(
                calculatorId, tenantId, frequency, historyLimit);

        // Cache for 30 seconds
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                .body(response);
    }

    /**
     * Batch endpoint for dashboard with partition pruning
     */
    @PostMapping("/batch/status")
    @Operation(
            summary = "Get status for multiple calculators",
            description = "Optimized batch query with partition pruning based on frequency"
    )
    @PreAuthorize("hasAnyRole('UI_READER', 'AIRFLOW')")
    public ResponseEntity<List<CalculatorStatusResponse>> getBatchCalculatorStatus(
            @RequestBody @NotEmpty @Size(max = 100) List<String> calculatorIds,
            @RequestParam @NotBlank String frequency,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int historyLimit,
            @Parameter(description = "Use stale cache (faster, may be slightly outdated)")
            @RequestParam(defaultValue = "false") boolean allowStale) {

        String tenantId = tenantContext.getCurrentTenantId();

        log.debug("Batch status query for {} calculators (frequency={}, allowStale={})",
                calculatorIds.size(), frequency, allowStale);

        meterRegistry.counter("api.calculators.batch.requests",
                "count", String.valueOf(calculatorIds.size()),
                "frequency", frequency,
                "allow_stale", String.valueOf(allowStale)
        ).increment();

        List<CalculatorStatusResponse> response = queryService.getBatchCalculatorStatus(
                calculatorIds, tenantId, frequency, historyLimit);

        // Aggressive caching for batch queries when stale is allowed
        CacheControl cacheControl = allowStale
                ? CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate()
                : CacheControl.maxAge(15, TimeUnit.SECONDS).cachePrivate();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .body(response);
    }
}