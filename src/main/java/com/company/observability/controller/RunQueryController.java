// File: src/main/java/com/company/observability/controller/RunQueryController.java
package com.company.observability.controller;

import com.company.observability.dto.request.BatchRecentRunsRequest;
import com.company.observability.dto.response.*;
import com.company.observability.service.RunQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Query", description = "Query calculator run information")
@RequiredArgsConstructor
public class RunQueryController {
    
    private final RunQueryService queryService;
    
    @GetMapping("/calculator/{calculatorId}/recent")
    @Operation(summary = "Get last N runs for a calculator")
    public ResponseEntity<LastNRunsResponse> getLastNRuns(
            @PathVariable String calculatorId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        List<RunSummaryResponse> runs = queryService.getLastNRuns(calculatorId, tenantId, limit);
        
        LastNRunsResponse response = LastNRunsResponse.builder()
            .calculatorId(calculatorId)
            .limit(limit)
            .count(runs.size())
            .runs(runs)
            .cachedAt(Instant.now())
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/batch/recent")
    @Operation(summary = "Get recent runs for multiple calculators")
    public ResponseEntity<BatchRecentRunsResponse> getBatchRecentRuns(
            @Valid @RequestBody BatchRecentRunsRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        Map<String, List<RunSummaryResponse>> results = 
            queryService.getBatchRecentRuns(request.getCalculatorIds(), tenantId, request.getLimit());
        
        BatchRecentRunsResponse response = BatchRecentRunsResponse.builder()
            .calculatorCount(request.getCalculatorIds().size())
            .limit(request.getLimit())
            .results(results)
            .queriedAt(Instant.now())
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/calculator/{calculatorId}/average-runtime")
    @Operation(summary = "Get average runtime with frequency-aware lookback")
    public ResponseEntity<AverageRuntimeResponse> getAverageRuntime(
            @PathVariable String calculatorId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        AverageRuntimeResponse response = queryService.getAverageRuntime(calculatorId, tenantId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{runId}")
    @Operation(summary = "Get calculator run by ID")
    public ResponseEntity<RunDetailResponse> getRunById(
            @PathVariable String runId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        RunDetailResponse response = queryService.getRunById(runId, tenantId);
        return ResponseEntity.ok(response);
    }
}