package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.exception.DomainValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlaBaselineResolverTest {

    private DurationBasedSlaProperties props;
    private SlaBaselineResolver resolver;

    private static final Instant START = Instant.parse("2026-02-22T04:00:00Z");
    private static final long LATE_BAND_MS = 15L * 60 * 1000;

    @BeforeEach
    void setUp() {
        props = new DurationBasedSlaProperties();
        resolver = new SlaBaselineResolver(props, new SimpleMeterRegistry());
    }

    private StartRunRequest.StartRunRequestBuilder baseRequest() {
        return StartRunRequest.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(LocalDate.of(2026, 2, 22))
                .startTime(START);
    }

    private CalculatorProfile profile(long avgDurationMs, int totalRuns) {
        return new CalculatorProfile("calc-1", "DAILY", avgDurationMs, 0, 0, totalRuns);
    }

    private static final CalculatorProfile EMPTY = new CalculatorProfile("calc-1", "DAILY", 0, 0, 0, 0);

    @Test
    void slaTimeDuration_derivesBaselineAndDeadline() {
        StartRunRequest request = baseRequest().slaTime("PT2H30M").build();

        SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

        assertThat(result.baselineDurationMs()).isEqualTo(9_000_000L);
        assertThat(result.deadline()).isEqualTo(START.plusMillis(10_800_000L + LATE_BAND_MS));
    }

    @Test
    void slaTimeWinsOverExpectedDurationWhenBothPresent() {
        StartRunRequest request = baseRequest()
                .slaTime("PT1H")
                .expectedDurationMs(3_600_000L * 3)
                .build();

        SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

        assertThat(result.baselineDurationMs()).isEqualTo(3_600_000L);
    }

    @Test
    void expectedDurationUsedWhenSlaTimeAbsent() {
        StartRunRequest request = baseRequest().expectedDurationMs(300_000L).build();

        SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

        assertThat(result.baselineDurationMs()).isEqualTo(300_000L);
        assertThat(result.deadline()).isEqualTo(START.plusMillis(360_000L + LATE_BAND_MS));
    }

    @Test
    void profileAverageUsedWhenExternalFieldsMissing() {
        SlaBaselineResolver.SlaResolution result = resolver.resolve(
                baseRequest().build(), Frequency.DAILY, profile(600_000L, 10));

        assertThat(result.baselineDurationMs()).isEqualTo(600_000L);
        assertThat(result.deadline()).isEqualTo(START.plusMillis(720_000L + LATE_BAND_MS));
    }

    @Test
    void legacyInstantIsRejected() {
        Instant legacyDeadline = START.plusSeconds(30 * 60);
        StartRunRequest request = baseRequest().slaTime(legacyDeadline.toString()).build();

        assertThrows(DomainValidationException.class,
                () -> resolver.resolve(request, Frequency.DAILY, EMPTY));
    }

    @Test
    void invalidSlaTimeFailsValidation() {
        StartRunRequest request = baseRequest().slaTime("22:00").build();

        assertThrows(DomainValidationException.class,
                () -> resolver.resolve(request, Frequency.DAILY, EMPTY));
    }

    @Test
    void zeroOrNegativeSlaTimeFailsValidation() {
        StartRunRequest zero = baseRequest().slaTime("PT0S").build();
        StartRunRequest negative = baseRequest().slaTime("PT-5M").build();

        assertThrows(DomainValidationException.class,
                () -> resolver.resolve(zero, Frequency.DAILY, EMPTY));
        assertThrows(DomainValidationException.class,
                () -> resolver.resolve(negative, Frequency.DAILY, EMPTY));
    }

    @Test
    void noBaselineAtAll_returnsUngradedResolution() {
        SlaBaselineResolver.SlaResolution result = resolver.resolve(baseRequest().build(), Frequency.DAILY, EMPTY);

        assertThat(result.deadline()).isNull();
        assertThat(result.baselineDurationMs()).isNull();
    }

    @Test
    void disabled_returnsUngraded() {
        props.setEnabled(false);
        StartRunRequest request = baseRequest().slaTime("PT45M").build();

        SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, profile(600_000L, 10));

        assertThat(result.deadline()).isNull();
        assertThat(result.baselineDurationMs()).isNull();
    }
}
