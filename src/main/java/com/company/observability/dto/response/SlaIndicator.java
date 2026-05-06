package com.company.observability.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record SlaIndicator(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant deadlineTime,
        boolean breached
) {}
