package com.company.observability.controller;

import com.company.observability.dto.response.PartitionOperationResponse;
import com.company.observability.scheduled.DailyAggregationJob;
import com.company.observability.service.PartitionMaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/maintenance")
@RequiredArgsConstructor
@Tag(name = "Maintenance", description = "Ops endpoints for partition lifecycle management and job triggers. Requires ADMIN role.")
public class MaintenanceController {

    private final PartitionMaintenanceService service;
    private final DailyAggregationJob dailyAggregationJob;

    @PostMapping("/jobs/daily-aggregation")
    @Operation(summary = "Trigger daily aggregation", description = "Manually runs the nightly DailyAggregationJob: recomputes calculator_sli_daily and warms the profile cache. Idempotent.")
    public ResponseEntity<String> triggerDailyAggregation() {
        dailyAggregationJob.runDailyAggregation();
        return ResponseEntity.ok("daily aggregation triggered");
    }

    @PostMapping("/partitions/create")
    @Operation(summary = "Create partitions", description = "Creates calculator_runs partitions for the next 60 days. Idempotent.")
    public ResponseEntity<PartitionOperationResponse> createPartitions() {
        return ResponseEntity.ok(service.createPartitions());
    }

    @PostMapping("/partitions/drop")
    @Operation(summary = "Drop old partitions", description = "Drops calculator_runs partitions older than 395 days. Idempotent.")
    public ResponseEntity<PartitionOperationResponse> dropPartitions() {
        return ResponseEntity.ok(service.dropPartitions());
    }

    @GetMapping("/partitions/stats")
    @Operation(summary = "Partition statistics", description = "Returns row counts and sizes for the most recent 30 partitions.")
    public ResponseEntity<PartitionOperationResponse> getStats() {
        return ResponseEntity.ok(service.getStats());
    }
}
