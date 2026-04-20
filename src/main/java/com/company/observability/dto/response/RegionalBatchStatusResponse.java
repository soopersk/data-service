package com.company.observability.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;


public record RegionalBatchStatusResponse(
        LocalDate reportingDate,
        String reportingDateFormatted,
        OverallSla overallSla,
        TimeReference estimatedStart,
        TimeReference estimatedEnd,
        int totalRegions,
        int completedRegions,
        int runningRegions,
        int failedRegions,
        List<RegionStatus> regions,
        List<String> slaBreachedRegions
) {

    public record OverallSla(
            String deadlineTimeCet,
            boolean breached
    ) {}

    public record RegionStatus(
            String region,
            String runId,
            String status,
            String startTimeCet,
            String endTimeCet,
            BigDecimal startHourCet,
            BigDecimal endHourCet,
            Long durationMs,
            String durationFormatted,
            String runDay,
            String batchType,
            boolean slaBreached
    ) {}
}
