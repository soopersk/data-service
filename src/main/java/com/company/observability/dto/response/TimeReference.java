package com.company.observability.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * A UTC time reference that may be an actual observed value or a median-based prediction.
 * Shared by {@link RegionalBatchStatusResponse} and {@link CalculatorDashboardResponse}.
 *
 * @param actual true if derived from today's actual run data, false if predicted from history
 */
public record TimeReference(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant time,
        String basedOn,
        boolean actual
) {}
