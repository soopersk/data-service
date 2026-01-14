package com.company.observability.dto.request;

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
public class CompleteRunRequest {
    @NotNull(message = "End time is required")
    private Instant endTime;
    
    private String status; // SUCCESS, FAILED, TIMEOUT
}