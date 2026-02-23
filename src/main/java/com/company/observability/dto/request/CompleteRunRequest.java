package com.company.observability.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    @Pattern(
            regexp = "(?i)SUCCESS|FAILED|TIMEOUT|CANCELLED",
            message = "Status must be one of SUCCESS, FAILED, TIMEOUT, CANCELLED"
    )
    private String status; // Optional. Defaults to SUCCESS when omitted.
}
