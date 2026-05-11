package com.company.observability.dto.response;

import java.time.LocalDate;
import java.util.List;

public record PartitionOperationResponse(
        String operation,
        long durationMs,
        int partitionCount,
        List<PartitionStat> stats
) {
    public record PartitionStat(
            String partitionName,
            LocalDate partitionDate,
            long rowCount,
            String totalSize,
            long dailyRuns,
            long monthlyRuns
    ) {}
}
