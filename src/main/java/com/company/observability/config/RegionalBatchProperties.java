package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "observability.regional-batch")
@Getter
@Setter
public class RegionalBatchProperties {

    /**
     * Overall SLA deadline as CET time-of-day (e.g., 17:45).
     * All regional batches must complete before this time.
     */
    private LocalTime overallSlaTimeCet = LocalTime.of(17, 45);

    /**
     * Ordered list of region codes for display.
     */
    private List<String> regionOrder = List.of(
            "WMAP", "WMDE", "ASIA", "WMUS", "AUNZ",
            "WMCH", "ZURI", "LDNL", "AMER", "EURO"
    );
}
