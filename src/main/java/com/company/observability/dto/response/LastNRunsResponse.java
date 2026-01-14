package com.company.observability.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LastNRunsResponse {
    private String calculatorId;
    private Integer limit;
    private Integer count;
    private List<RunSummaryResponse> runs;
    private Instant cachedAt;
}