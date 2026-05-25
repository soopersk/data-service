package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.exception.DomainValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

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
 * <p>Baseline resolution is a fallback chain:
 * <ol>
 *   <li><b>Request slaTime</b> — preferred external input as ISO-8601 duration
 *       (for example, {@code PT2H30M}).</li>
 *   <li><b>expectedDurationMs</b> — external duration fallback.</li>
 *   <li><b>Average</b> — the cached {@link CalculatorProfile}'s avg duration, gated by
 *       {@code minSampleSize}.</li>
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

    public record SlaResolution(
            Long baselineDurationMs,
            Instant deadline
    ) {}

    /**
     * @param profile the calculator's cached rolling profile (may be a zero-sample sentinel)
     * @return baseline + deadline resolution details for persistence and estimated-end fallback.
     */
    public SlaResolution resolve(StartRunRequest request, Frequency frequency, CalculatorProfile profile) {
        // Feature disabled -> do not derive a deadline.
        if (!props.isEnabled()) {
            return new SlaResolution(null, null);
        }

        Instant startTime = request.getStartTime();
        if (startTime == null) {
            return new SlaResolution(null, null);
        }

        Long baselineMs = resolveBaselineMs(request, profile);
        if (baselineMs == null) {
            meterRegistry.counter("obs.sla.baseline.ungraded", "frequency", frequency.name()).increment();
            log.debug("event=sla.baseline.resolve outcome=ungraded calculatorId={} frequency={}",
                    request.getCalculatorId(), frequency);
            return new SlaResolution(null, null);
        }

        long bufferedMs = Math.round(baselineMs * (1 + props.getThresholdPercent() / 100.0));
        Instant deadline = startTime.plusMillis(bufferedMs + props.lateBandMs());

        log.debug("event=sla.baseline.resolve outcome=success calculatorId={} frequency={} baselineMs={} deadline={}",
                request.getCalculatorId(), frequency, baselineMs, deadline);
        return new SlaResolution(baselineMs, deadline);
    }

    private Long resolveBaselineMs(StartRunRequest request, CalculatorProfile profile) {
        // 1. Request slaTime duration
        if (hasText(request.getSlaTime())) {
            return parseRequestSlaBaseline(request.getSlaTime());
        }

        // 2. expectedDurationMs
        if (request.getExpectedDurationMs() != null && request.getExpectedDurationMs() > 0) {
            return request.getExpectedDurationMs();
        }

        // 3. Average path (cached profile)
        if (profile != null && profile.hasSufficientSamples(props.getMinSampleSize()) && profile.avgDurationMs() > 0) {
            return profile.avgDurationMs();
        }

        // 4. None
        return null;
    }

    private Long parseRequestSlaBaseline(String rawSlaTime) {
        String value = rawSlaTime.trim();

        try {
            Duration duration = Duration.parse(value);
            long durationMs = duration.toMillis();
            if (durationMs <= 0) {
                throw new DomainValidationException("slaTime must be a positive ISO-8601 duration");
            }
            return durationMs;
        } catch (DateTimeParseException ex) {
            throw new DomainValidationException("Invalid slaTime. Use ISO-8601 duration (for example PT2H30M).");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
