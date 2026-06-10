package com.company.observability.controller;

import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.service.ExpectedRunsService;
import com.company.observability.service.CalculatorNameResolver;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Query controller with enum support
 */
@RestController
@RequestMapping("/api/v1/calculators")
@Tag(name = "Calculator Status", description = "Query calculator runtime status and history")
@RequiredArgsConstructor
@Validated
@Slf4j
public class RunQueryController {

    private final RunQueryService queryService;
    private final CalculatorStateService calculatorStateService;
    private final CalculatorNameResolver nameResolver;
    private final ExpectedRunsService expectedRunsService;
    private final MeterRegistry meterRegistry;

    @GetMapping("/{calculatorId}/status")
    @Operation(
            summary = "Get calculator status with current run and history",
            description = "Returns last N runs based on frequency (DAILY: 2-3 days, MONTHLY: end-of-month). " +
                         "Set bypassCache=true to force fresh data from database."
    )
    public ResponseEntity<CalculatorStatusResponse> getCalculatorStatus(
            @PathVariable String calculatorId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,

            @Parameter(description = "Frequency: DAILY, MONTHLY, D, or M (case-insensitive)")
            @RequestParam String frequency,

            @Parameter(description = "Number of historical runs to return (1-100)")
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int historyLimit,

            @Parameter(description = "Bypass all caches and force database query")
            @RequestParam(defaultValue = "false") boolean bypassCache) {

        // Parse frequency using enum's built-in parser
        Frequency freq = Frequency.fromStrict(frequency);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CalculatorStatusResponse response = queryService.getCalculatorStatus(
                    calculatorId, freq, historyLimit, bypassCache);

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
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,

            @Parameter(description = "Frequency: DAILY, MONTHLY, D, or M")
            @RequestParam String frequency,

            @Parameter(description = "Number of historical runs per calculator (1-50)")
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int historyLimit,

            @Parameter(description = "Allow stale cached data. Set false to force fresh queries.")
            @RequestParam(defaultValue = "true") boolean allowStale) {

        // Parse frequency using enum
        Frequency freq = Frequency.fromStrict(frequency);

        log.debug("event=query.batch outcome=accepted count={} frequency={} allowStale={}",
                calculatorIds.size(), freq, allowStale);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<CalculatorStatusResponse> response = queryService.getBatchCalculatorStatus(
                    calculatorIds, freq, historyLimit, allowStale);

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
                    "The `keys` query param is a pipe-separated list of calculator_name values (readable, " +
                    "unique-per-tenant); upstream UUIDs are not accepted on this endpoint. " +
                    "Regional calculators return one RunEntry per region; typed calculators return one per runType. " +
                    "Empty runs list = no run found. isRerun=true = a re-trigger was fired for that dimension."
    )
    public ResponseEntity<CalculatorBatchRunsResponse> getBatchRuns(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam("reporting_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate,
            @RequestParam(defaultValue = "DAILY") String frequency,
            @RequestParam(value = "run_number", required = false) String runNumber,
            @Parameter(description = "Pipe-separated calculator_name values, e.g. capitalcalc|portfoliocalc")
            @RequestParam @NotBlank String keys) {

        List<String> aliases = Arrays.stream(keys.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (aliases.isEmpty()) {
            throw new IllegalArgumentException("keys must contain at least one non-blank calculator name");
        }

        Frequency freq = Frequency.fromStrict(frequency);

        // Normalize blank → null once so getState and padToExpected see the same value.
        // Avoids run_number='' triggering wasted dimension/scoped Redis+DB lookups in padToExpected.
        runNumber = (runNumber == null || runNumber.isBlank()) ? null : runNumber;

        // Expand aliases → {alias: [realName, ...]}; unknown names pass through unchanged
        Map<String, List<String>> aliasToRealNames = nameResolver.resolveAll(aliases);

        List<String> allRealNames = aliasToRealNames.values().stream()
                .flatMap(Collection::stream)
                .distinct()
                .toList();

        log.info("event=batch_runs.request outcome=accepted reportingDate={} frequency={} aliasCount={} realNameCount={} runNumber={}",
                reportingDate, freq, aliases.size(), allRealNames.size(), runNumber);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Map<String, CalculatorBatchRunsResponse.CalculatorEntry> byRealName =
                    calculatorStateService.getState(reportingDate, freq, runNumber, allRealNames);

            // Re-group by alias: merge entries from all real names under each alias key
            Map<String, CalculatorBatchRunsResponse.CalculatorEntry> calculators =
                    aliasToRealNames.entrySet().stream().collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> mergeEntries(e.getKey(), e.getValue(), byRealName),
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));

            // Pad each configured alias to its full declared set of expected runs
            calculators = expectedRunsService.padToExpected(calculators, reportingDate, freq, runNumber);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                    .body(new CalculatorBatchRunsResponse(
                            reportingDate, freq.name(), runNumber, Instant.now(), calculators));
        } finally {
            sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                    "endpoint", "/calculators/batch/runs"));
        }
    }

    private CalculatorBatchRunsResponse.CalculatorEntry mergeEntries(
            String alias,
            List<String> realNames,
            Map<String, CalculatorBatchRunsResponse.CalculatorEntry> byRealName) {

        List<CalculatorBatchRunsResponse.CalculatorEntry> parts = realNames.stream()
                .map(byRealName::get)
                .filter(Objects::nonNull)
                .toList();

        if (parts.isEmpty()) {
            return new CalculatorBatchRunsResponse.CalculatorEntry(alias, null, List.of());
        }
        if (parts.size() == 1) {
            CalculatorBatchRunsResponse.CalculatorEntry single = parts.get(0);
            return new CalculatorBatchRunsResponse.CalculatorEntry(alias, single.calculatorId(), single.runs());
        }

        // Multi: merge runs; calculatorId only when all real calculators agree (unusual)
        Set<String> ids = parts.stream()
                .map(CalculatorBatchRunsResponse.CalculatorEntry::calculatorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        String mergedId = ids.size() == 1 ? ids.iterator().next() : null;

        List<CalculatorBatchRunsResponse.RunEntry> allRuns = parts.stream()
                .flatMap(e -> e.runs().stream())
                .toList();

        return new CalculatorBatchRunsResponse.CalculatorEntry(alias, mergedId, allRuns);
    }
}
