package com.company.observability.config;

import com.company.observability.domain.enums.SlaMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Top-level SLA mode selector. Determines how {@code StartRunRequest.slaTime} is interpreted.
 * Does not collide with {@link DurationBasedSlaProperties} ({@code observability.sla.duration-based.*}).
 */
@Component
@ConfigurationProperties(prefix = "observability.sla")
@Getter
@Setter
public class SlaProperties {

    /** Global SLA interpretation mode. Default CLOCK_TIME for phase-1. */
    private SlaMode mode = SlaMode.CLOCK_TIME;
}
