package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.request.StartRunRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SlaBaselineResolverTest {

    private DurationBasedSlaProperties props;
    private SlaBaselineResolver resolver;

    private static final Instant START = Instant.parse("2026-02-22T04:00:00Z");
    // Defaults: thresholdPercent=20, lateBand=15m, minSampleSize=5.
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
                .frequency(CalculatorFrequency.DAILY)
                .reportingDate(LocalDate.of(2026, 2, 22))
                .startTime(START);
    }

    private CalculatorProfile profile(long avgDurationMs, int totalRuns) {
        return new CalculatorProfile("calc-1", "DAILY", avgDurationMs, 0, 0, totalRuns);
    }

    private static final CalculatorProfile EMPTY = new CalculatorProfile("calc-1", "DAILY", 0, 0, 0, 0);

    @Test
    void averagePath_derivesDeadlineFromProfileAverage() {
        // avg = 600_000ms (10 min); buffered = *1.2 = 720_000ms (12 min)
        Instant deadline = resolver.resolveDeadline(
                baseRequest().build(), CalculatorFrequency.DAILY, profile(600_000L, 10));

        assertThat(deadline).isEqualTo(START.plusMillis(720_000L + LATE_BAND_MS));
    }

    @Test
    void insufficientSamples_fallsBackToExpectedDuration() {
        StartRunRequest request = baseRequest().expectedDurationMs(300_000L).build(); // 5 min

        Instant deadline = resolver.resolveDeadline(
                request, CalculatorFrequency.DAILY, profile(600_000L, 2)); // below minSampleSize=5

        // buffered = 300_000 * 1.2 = 360_000
        assertThat(deadline).isEqualTo(START.plusMillis(360_000L + LATE_BAND_MS));
    }

    @Test
    void noAverageNoExpected_fallsBackToSlaTimeBudget() {
        Instant slaTime = START.plusSeconds(30 * 60); // budget = 1_800_000ms
        StartRunRequest request = baseRequest().slaTime(slaTime).build();

        Instant deadline = resolver.resolveDeadline(request, CalculatorFrequency.DAILY, EMPTY);

        // buffered = 1_800_000 * 1.2 = 2_160_000
        assertThat(deadline).isEqualTo(START.plusMillis(2_160_000L + LATE_BAND_MS));
    }

    @Test
    void noBaselineAtAll_returnsNull() {
        Instant deadline = resolver.resolveDeadline(baseRequest().build(), CalculatorFrequency.DAILY, EMPTY);
        assertThat(deadline).isNull();
    }

    @Test
    void slaTimeBeforeStart_isUnusable_returnsNull() {
        StartRunRequest request = baseRequest().slaTime(START.minusSeconds(60)).build();

        Instant deadline = resolver.resolveDeadline(request, CalculatorFrequency.DAILY, EMPTY);

        assertThat(deadline).isNull();
    }

    @Test
    void disabled_passesThroughRequestSlaTime() {
        props.setEnabled(false);
        Instant slaTime = START.plusSeconds(45 * 60);
        StartRunRequest request = baseRequest().slaTime(slaTime).build();

        Instant deadline = resolver.resolveDeadline(request, CalculatorFrequency.DAILY, profile(600_000L, 10));

        assertThat(deadline).isEqualTo(slaTime);
    }
}
