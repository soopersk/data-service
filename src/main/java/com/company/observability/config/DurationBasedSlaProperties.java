package com.company.observability.config;

import com.company.observability.domain.enums.CalculatorFrequency;
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
 */
@Component
@ConfigurationProperties(prefix = "observability.sla.duration-based")
@Getter
@Setter
public class DurationBasedSlaProperties {

    /** When false, the resolver passes the request's slaTime through unchanged (legacy behavior). */
    private boolean enabled = true;

    /** Percentage buffer applied over the resolved baseline (e.g. 20 → baseline * 1.20). */
    private double thresholdPercent = 20;

    /** ON_TIME upper edge beyond the buffered baseline, in minutes. Baked into the frozen slaTime. */
    private int lateBandMinutes = 15;

    /** LATE upper edge beyond the buffered baseline, in minutes. */
    private int veryLateBandMinutes = 30;

    /** Runs required in the window before the historical average is trusted. */
    private int minSampleSize = 5;

    private Lookback lookback = new Lookback();

    public long lateBandMs() {
        return lateBandMinutes * 60_000L;
    }

    /** Width of the LATE band: gap between the LATE edge (frozen into slaTime) and the VERY_LATE edge. */
    public long bandGapMs() {
        return (long) (veryLateBandMinutes - lateBandMinutes) * 60_000L;
    }

    public int lookbackDays(CalculatorFrequency frequency) {
        return frequency == CalculatorFrequency.MONTHLY
                ? lookback.getMonthlyDays()
                : lookback.getDailyDays();
    }

    @Getter
    @Setter
    public static class Lookback {
        private int dailyDays = 30;
        private int monthlyDays = 395;
    }
}
