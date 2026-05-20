package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.repository.DailyAggregateRepository;
import com.company.observability.repository.DailyAggregateRepository.AverageDuration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SlaBaselineResolverTest {

    @Mock
    private DailyAggregateRepository dailyAggregateRepository;

    private DurationBasedSlaProperties props;
    private SlaBaselineResolver resolver;

    private static final Instant START = Instant.parse("2026-02-22T04:00:00Z");
    // Defaults: thresholdPercent=20, lateBand=15m, minSampleSize=5.
    private static final long LATE_BAND_MS = 15L * 60 * 1000;

    @BeforeEach
    void setUp() {
        props = new DurationBasedSlaProperties();
        resolver = new SlaBaselineResolver(dailyAggregateRepository, props, new SimpleMeterRegistry());
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

    private void stubAverage(long sumMs, int totalRuns) {
        lenient().when(dailyAggregateRepository.findAverageDuration(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new AverageDuration(sumMs, totalRuns));
    }

    @Test
    void averagePath_derivesDeadlineFromHistoricalAverage() {
        // avg = 6_000_000 / 10 = 600_000ms (10 min); buffered = *1.2 = 720_000ms (12 min)
        stubAverage(6_000_000L, 10);

        Instant deadline = resolver.resolveDeadline(baseRequest().build(), "tenant-1", CalculatorFrequency.DAILY);

        assertThat(deadline).isEqualTo(START.plusMillis(720_000L + LATE_BAND_MS));
    }

    @Test
    void insufficientSamples_fallsBackToExpectedDuration() {
        stubAverage(6_000_000L, 2); // below minSampleSize=5
        StartRunRequest request = baseRequest().expectedDurationMs(300_000L).build(); // 5 min

        Instant deadline = resolver.resolveDeadline(request, "tenant-1", CalculatorFrequency.DAILY);

        // buffered = 300_000 * 1.2 = 360_000
        assertThat(deadline).isEqualTo(START.plusMillis(360_000L + LATE_BAND_MS));
    }

    @Test
    void noAverageNoExpected_fallsBackToSlaTimeBudget() {
        stubAverage(0L, 0);
        Instant slaTime = START.plusSeconds(30 * 60); // budget = 1_800_000ms
        StartRunRequest request = baseRequest().slaTime(slaTime).build();

        Instant deadline = resolver.resolveDeadline(request, "tenant-1", CalculatorFrequency.DAILY);

        // buffered = 1_800_000 * 1.2 = 2_160_000
        assertThat(deadline).isEqualTo(START.plusMillis(2_160_000L + LATE_BAND_MS));
    }

    @Test
    void noBaselineAtAll_returnsNull() {
        stubAverage(0L, 0);
        Instant deadline = resolver.resolveDeadline(baseRequest().build(), "tenant-1", CalculatorFrequency.DAILY);
        assertThat(deadline).isNull();
    }

    @Test
    void slaTimeBeforeStart_isUnusable_returnsNull() {
        stubAverage(0L, 0);
        StartRunRequest request = baseRequest().slaTime(START.minusSeconds(60)).build();

        Instant deadline = resolver.resolveDeadline(request, "tenant-1", CalculatorFrequency.DAILY);

        assertThat(deadline).isNull();
    }

    @Test
    void disabled_passesThroughRequestSlaTime() {
        props.setEnabled(false);
        Instant slaTime = START.plusSeconds(45 * 60);
        StartRunRequest request = baseRequest().slaTime(slaTime).build();

        Instant deadline = resolver.resolveDeadline(request, "tenant-1", CalculatorFrequency.DAILY);

        assertThat(deadline).isEqualTo(slaTime);
    }

    @Test
    void monthly_usesMonthlyLookbackWindow() {
        stubAverage(6_000_000L, 10);

        resolver.resolveDeadline(baseRequest().frequency(CalculatorFrequency.MONTHLY).build(),
                "tenant-1", CalculatorFrequency.MONTHLY);

        // monthly lookback default = 395
        org.mockito.Mockito.verify(dailyAggregateRepository)
                .findAverageDuration(eq("calc-1"), eq("tenant-1"), eq("MONTHLY"), eq(395));
    }
}
