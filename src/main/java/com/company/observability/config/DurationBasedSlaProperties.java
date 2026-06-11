package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for duration-based (average-runtime) SLA grading.
 *
 * <p>An SLA deadline is derived at run start from the calculator's own historical
 * average runtime plus a percentage buffer and a grace band, rather than from an
 * absolute instant supplied upstream. The derived deadline is frozen into the run's
 * {@code slaTime} so the existing on-write and live-detection machinery is reused.
 *
 * <p>Band and profile-window knobs shared across all spec kinds live on
 * {@link SlaProperties} ({@code observability.sla.*}); only the duration-exclusive
 * {@code thresholdPercent} remains here.
 */
@Component
@ConfigurationProperties(prefix = "observability.sla.duration-based")
@Getter
@Setter
public class DurationBasedSlaProperties {

    /** Percentage buffer applied over the resolved baseline (e.g. 20 → baseline * 1.20). */
    private double thresholdPercent = 20;
}
