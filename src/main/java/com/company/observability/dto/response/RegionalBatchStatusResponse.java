package com.company.observability.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;


public record RegionalBatchStatusResponse(
        LocalDate reportingDate,
        String reportingDateFormatted,
        SlaIndicator overallSla,
        TimeReference estimatedStart,
        TimeReference estimatedEnd,
        int totalRegions,
        int completedRegions,
        int runningRegions,
        int failedRegions,
        List<RegionStatus> regions,
        List<String> slaBreachedRegions
) {

    public record RegionStatus(
            String region,
            String runId,
            String status,
            Instant startTime,
            Instant endTime,
            Long durationMs,
            String durationFormatted,
            String runDay,
            String batchType,
            boolean slaBreached
    ) {}
}
