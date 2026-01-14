// File: src/main/java/com/company/observability/controller/RunIngestionController.java
package com.company.observability.controller;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.request.CompleteRunRequest;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.dto.response.RunResponse;
import com.company.observability.service.RunIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Ingestion", description = "APIs for Airflow to ingest calculator run data")
@RequiredArgsConstructor
public class RunIngestionController {
    
    private final RunIngestionService ingestionService;
    
    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a calculator run (called by Airflow)")
    public ResponseEntity<RunResponse> startRun(
            @Valid @RequestBody StartRunRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        CalculatorRun run = ingestionService.startRun(request, tenantId);
        
        return ResponseEntity
            .created(URI.create("/api/v1/runs/" + run.getRunId()))
            .body(toRunResponse(run));
    }
    
    @PostMapping("/{runId}/complete")
    @Operation(summary = "Complete a calculator run (called by Airflow)")
    public ResponseEntity<RunResponse> completeRun(
            @PathVariable String runId,
            @Valid @RequestBody CompleteRunRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        CalculatorRun run = ingestionService.completeRun(runId, request, tenantId);
        
        return ResponseEntity.ok(toRunResponse(run));
    }
    
    private RunResponse toRunResponse(CalculatorRun run) {
        return RunResponse.builder()
            .runId(run.getRunId())
            .calculatorId(run.getCalculatorId())
            .calculatorName(run.getCalculatorName())
            .status(run.getStatus())
            .startTime(run.getStartTime())
            .endTime(run.getEndTime())
            .durationMs(run.getDurationMs())
            .slaBreached(run.getSlaBreached())
            .slaBreachReason(run.getSlaBreachReason())
            .build();
    }
}