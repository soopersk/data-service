package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.SlaMode;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.exception.DomainValidationException;
import com.company.observability.util.TimeUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

/**
 * Resolves a run's SLA deadline at start time and freezes it into {@code slaTime} so the
 * rest of the SLA machinery (on-write evaluation, Redis live detection) operates on a
 * single absolute instant.
 *
 * <p><b>CLOCK_TIME mode (phase-1 default):</b> {@code slaTime} in the request is a UTC
 * clock time {@code HH:mm}. The deadline is anchored to the run's start date and rolled
 * forward +1 day when the clock time is at or before {@code startTime} (overnight SLAs).
 * {@code baselineDurationMs} is null in this mode — SLA is not a duration.
 *
 * <p><b>DURATION mode (phase-2 / legacy):</b> {@code slaTime} is an ISO-8601 duration
 * (e.g. {@code PT2H30M}). Deadline = {@code startTime + buffered + lateBand}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaBaselineResolver {

    private final DurationBasedSlaProperties props;
    private final SlaProperties slaProperties;
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
        Instant startTime = request.getStartTime();
        if (startTime == null) {
            return new SlaResolution(null, null);
        }

        if (slaProperties.getMode() == SlaMode.CLOCK_TIME) {
            return resolveClockTime(request);
        }

        // DURATION mode — original logic
        if (!props.isEnabled()) {
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

    private SlaResolution resolveClockTime(StartRunRequest request) {
        String raw = request.getSlaTime();
        if (raw == null || raw.isBlank()) {
            return new SlaResolution(null, null);
        }

        if (request.getReportingDate() == null) {
            return new SlaResolution(null, null);
        }

        LocalTime parsed;
        try {
            parsed = LocalTime.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new DomainValidationException(
                    "Invalid slaTime. Use clock time HH:mm (UTC), e.g. 22:00.");
        }

        int n = parseRunNumber(request.getRunNumber());
        ZoneId zone = ZoneId.of(slaProperties.getSlaTimezone());
        java.time.LocalDate executionDate = TimeUtils.nextBusinessDay(request.getReportingDate(), n);
        Instant deadline = ZonedDateTime.of(executionDate, parsed, zone).toInstant();

        log.debug("event=sla.baseline.resolve mode=CLOCK_TIME calculatorId={} slaTime={} runNumber={} executionDate={} deadline={}",
                request.getCalculatorId(), raw, n, executionDate, deadline);
        return new SlaResolution(null, deadline);
    }

    /**
     * Parses run_number string to an integer offset for business-day advancement.
     * Null or non-numeric → 2 (T+2 default, matching {@code COALESCE(run_number, '2')} convention).
     */
    static int parseRunNumber(String runNumber) {
        if (runNumber == null || runNumber.isBlank()) {
            return 2;
        }
        try {
            int n = Integer.parseInt(runNumber.trim());
            return n > 0 ? n : 2;
        } catch (NumberFormatException e) {
            return 2;
        }
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
