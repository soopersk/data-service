package com.company.observability.scheduled;

import com.company.observability.config.AggregationProperties;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.repository.DailyAggregateRepository;
import com.company.observability.service.CalculatorProfileService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyAggregationJobTest {

    @Mock private DailyAggregateRepository dailyAggregateRepository;
    @Mock private CalculatorProfileService calculatorProfileService;

    private DailyAggregationJob job;

    @BeforeEach
    void setUp() {
        job = new DailyAggregationJob(
                dailyAggregateRepository, calculatorProfileService,
                new AggregationProperties(), new DurationBasedSlaProperties(), new SimpleMeterRegistry());
        job.registerGauges();
    }

    @Test
    void runDailyAggregation_recomputesTrailingWindow_andWarmsProfiles() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(3); // default recompute-window-days

        when(dailyAggregateRepository.recomputeForDateRange(from, today)).thenReturn(5);
        CalculatorProfile dailyProfile = new CalculatorProfile("calc-1", "t1", "DAILY", 100L, 0, 0, 10);
        CalculatorProfile monthlyProfile = new CalculatorProfile("calc-1", "t1", "MONTHLY", 200L, 0, 0, 3);
        when(dailyAggregateRepository.findAllProfiles("DAILY", 30)).thenReturn(List.of(dailyProfile));
        when(dailyAggregateRepository.findAllProfiles("MONTHLY", 395)).thenReturn(List.of(monthlyProfile));

        job.runDailyAggregation();

        verify(dailyAggregateRepository).recomputeForDateRange(eq(from), eq(today));
        verify(calculatorProfileService).warm(dailyProfile);
        verify(calculatorProfileService).warm(monthlyProfile);
        verify(calculatorProfileService, times(2)).warm(org.mockito.ArgumentMatchers.any());
    }
}
