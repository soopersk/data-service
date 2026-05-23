package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.request.StartRunRequest;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Resolves a run's SLA deadline at start time from a duration baseline, then freezes it
 * into {@code slaTime} so the rest of the SLA machinery (on-write evaluation, Redis live
 * detection) continues to operate on a single absolute instant.
 *
 * <p>The deadline marks the ON_TIME upper edge (entry into the LATE band):
 * <pre>
 *   baseline  = best available duration estimate (see below)
 *   buffered  = baseline * (1 + thresholdPercent/100)
 *   deadline  = startTime + buffered + lateBand
 * </pre>
 *
 * <p>Baseline resolution is a fallback chain, best estimate first:
 * <ol>
 *   <li><b>Average</b> — the cached {@link CalculatorProfile}'s avg duration, gated by
 *       {@code minSampleSize}.</li>
 *   <li><b>expectedDurationMs</b> — already a duration; cleanest stand-in for the average.</li>
 *   <li><b>slaTime budget</b> — {@code slaTime - startTime}; last resort (start-time contaminated).</li>
 *   <li><b>None</b> — no deadline; the run is left ungraded (ON_TIME) until history accrues.</li>
 * </ol>
 *
 * <p>The profile is fetched once by {@code RunIngestionService} (Redis-cached) and passed in,
 * so the resolver issues no DB query of its own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaBaselineResolver {

    private final DurationBasedSlaProperties props;
    private final MeterRegistry meterRegistry;

    /**
     * @param profile the calculator's cached rolling profile (may be a zero-sample sentinel)
     * @return the derived SLA deadline (LATE-band edge), or {@code null} when no baseline is
     *         available and the run should not be graded.
     */
    public Instant resolveDeadline(StartRunRequest request, Frequency frequency, CalculatorProfile profile) {
        // Feature disabled → legacy passthrough of the upstream-supplied deadline.
        if (!props.isEnabled()) {
            return request.getSlaTime();
        }

        Instant startTime = request.getStartTime();
        if (startTime == null) {
            return null;
        }

        Long baselineMs = resolveBaselineMs(request, profile);
        if (baselineMs == null || baselineMs <= 0) {
            meterRegistry.counter("obs.sla.baseline.ungraded", "frequency", frequency.name()).increment();
            log.debug("event=sla.baseline.resolve outcome=ungraded calculatorId={} frequency={}",
                    request.getCalculatorId(), frequency);
            return null;
        }

        long bufferedMs = Math.round(baselineMs * (1 + props.getThresholdPercent() / 100.0));
        Instant deadline = startTime.plusMillis(bufferedMs + props.lateBandMs());

        log.debug("event=sla.baseline.resolve outcome=success calculatorId={} frequency={} baselineMs={} deadline={}",
                request.getCalculatorId(), frequency, baselineMs, deadline);
        return deadline;
    }

    private Long resolveBaselineMs(StartRunRequest request, CalculatorProfile profile) {
        // 1. Average path (cached profile)
        if (profile != null && profile.hasSufficientSamples(props.getMinSampleSize())) {
            return profile.avgDurationMs();
        }

        // 2. expectedDurationMs
        if (request.getExpectedDurationMs() != null && request.getExpectedDurationMs() > 0) {
            return request.getExpectedDurationMs();
        }

        // 3. slaTime budget (last resort)
        if (request.getSlaTime() != null) {
            long budgetMs = Duration.between(request.getStartTime(), request.getSlaTime()).toMillis();
            if (budgetMs > 0) {
                return budgetMs;
            }
        }

        // 4. None
        return null;
    }
}
