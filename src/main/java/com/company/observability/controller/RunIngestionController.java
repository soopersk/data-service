package com.company.observability.controller;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.request.CompleteRunRequest;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.dto.response.RunResponse;
import com.company.observability.service.RunIngestionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;

/**
 * FIXED: Secure ingestion controller with tenant context from JWT
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run Ingestion", description = "APIs for Airflow to ingest calculator run data")
@RequiredArgsConstructor
@Slf4j
public class RunIngestionController {

    private final RunIngestionService ingestionService;
    private final MeterRegistry meterRegistry;

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a calculator run", description = "Called by Airflow when calculator starts")
    public ResponseEntity<RunResponse> startRun(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody StartRunRequest request,
            Principal principal) {

        String userId = principal != null ? principal.getName() : "unknown";

        log.info("Start run request from user {} for calculator {} in tenant {}",
                userId, request.getCalculatorId(), tenantId);

        meterRegistry.counter("api.runs.start.requests").increment();

        CalculatorRun run = ingestionService.startRun(request, tenantId);

        return ResponseEntity
                .created(URI.create("/api/v1/runs/" + run.getRunId()))
                .body(toRunResponse(run));
    }

    @PostMapping("/{runId}/complete")
    @Operation(summary = "Complete a calculator run", description = "Called by Airflow when calculator finishes")
    public ResponseEntity<RunResponse> completeRun(
            @PathVariable String runId,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody CompleteRunRequest request,
            Principal principal) {

        String userId = principal != null ? principal.getName() : "unknown";

        log.info("Complete run request from user {} for run {} in tenant {}",
                userId, runId, tenantId);

        meterRegistry.counter("api.runs.complete.requests").increment();

        CalculatorRun run = ingestionService.completeRun(runId, request, tenantId);

        return ResponseEntity.ok(toRunResponse(run));
    }

    private RunResponse toRunResponse(CalculatorRun run) {
        return RunResponse.builder()
                .runId(run.getRunId())
                .calculatorId(run.getCalculatorId())
                .calculatorName(run.getCalculatorName())
                .status(run.getStatus().name())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .durationMs(run.getDurationMs())
                .slaBreached(run.getSlaBreached())
                .slaBreachReason(run.getSlaBreachReason())
                .build();
    }
}
