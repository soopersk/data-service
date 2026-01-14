package com.company.observability.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartRunRequest {
    @NotBlank(message = "Run ID is required")
    private String runId;
    
    @NotBlank(message = "Calculator ID is required")
    private String calculatorId;
    
    @NotNull(message = "Start time is required")
    private Instant startTime;
    
    private String runParameters;
}