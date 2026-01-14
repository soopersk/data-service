package com.company.observability.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRecentRunsResponse {
    private Integer calculatorCount;
    private Integer limit;
    private Map<String, List<RunSummaryResponse>> results;
    private Instant queriedAt;
}