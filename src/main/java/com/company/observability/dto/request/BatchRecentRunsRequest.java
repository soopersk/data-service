package com.company.observability.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRecentRunsRequest {
    @NotEmpty(message = "Calculator IDs cannot be empty")
    @Size(max = 100, message = "Maximum 100 calculators per request")
    private List<String> calculatorIds;
    
    @Min(1)
    @Max(50)
    private int limit = 10;
}