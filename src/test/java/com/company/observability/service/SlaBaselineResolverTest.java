package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.SlaMode;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.exception.DomainValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlaBaselineResolverTest {

    private DurationBasedSlaProperties durationProps;
    private SlaProperties slaProperties;
    private SlaBaselineResolver resolver;

    // reportingDate = 2026-02-22 (Sunday).
    // Default runNumber=null → n=2 → nextBusinessDay(Sun, 2) = Mon+1=Tue Feb 24.
    // Deadline for "22:00" UTC = 2026-02-24T22:00:00Z.
    private static final Instant START = Instant.parse("2026-02-22T04:00:00Z");
    private static final long LATE_BAND_MS = 15L * 60 * 1000;

    @BeforeEach
    void setUp() {
        durationProps = new DurationBasedSlaProperties();
        slaProperties = new SlaProperties(); // defaults to CLOCK_TIME, slaTimezone=UTC
        resolver = new SlaBaselineResolver(durationProps, slaProperties, new SimpleMeterRegistry());
    }

    private StartRunRequest.StartRunRequestBuilder baseRequest() {
        return StartRunRequest.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(LocalDate.of(2026, 2, 22))  // Sunday
                .startTime(START);
    }

    private CalculatorProfile profile(long avgDurationMs, int totalRuns) {
        return new CalculatorProfile("calc-1", "DAILY", null, avgDurationMs, 0, 0, totalRuns);
    }

    private static final CalculatorProfile EMPTY = new CalculatorProfile("calc-1", "DAILY", null, 0, 0, 0, 0);

    // -----------------------------------------------------------------------
    // CLOCK_TIME mode (phase-1 default) — business-calendar deadline
    // -----------------------------------------------------------------------

    @Nested
    class ClockTimeMode {

        @Test
        void clockTime_derivesUtcDeadlineViaBusinessCalendar() {
            // reportingDate=Sun 2026-02-22, n=2 (default) → T+2 = Tue 2026-02-24
            StartRunRequest request = baseRequest().slaTime("22:00").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-24T22:00:00Z"));
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void clockTime_runNumber1_givesT1Deadline() {
            // reportingDate=Sun 2026-02-22, n=1 → T+1 = Mon 2026-02-23
            StartRunRequest request = baseRequest().slaTime("22:00").runNumber("1").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-23T22:00:00Z"));
        }

        @Test
        void clockTime_runNumber2_givesT2Deadline() {
            // reportingDate=Mon 2026-02-23, n=2 → T+2 = Wed 2026-02-25
            StartRunRequest request = StartRunRequest.builder()
                    .runId("run-1").calculatorId("calc-1").calculatorName("Calculator 1")
                    .frequency(Frequency.DAILY)
                    .reportingDate(LocalDate.of(2026, 2, 23))  // Monday
                    .startTime(START)
                    .slaTime("09:30").runNumber("2").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-25T09:30:00Z"));
        }

        @Test
        void clockTimeWithSeconds_accepted() {
            // reportingDate=Sun 2026-02-22, n=2 → 2026-02-24
            StartRunRequest request = baseRequest().slaTime("22:00:30").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-24T22:00:30Z"));
        }

        @Test
        void overnight_noRolloverGuardNeeded_dateIsFixed() {
            // Old logic rolled forward +1 day when clock was before startTime.
            // New logic: date is fixed by reportingDate+runNumber — startTime is irrelevant.
            // reportingDate=Sun, n=2 → deadline on Tue 2026-02-24 at 02:00, regardless of startTime=04:00
            StartRunRequest request = baseRequest().slaTime("02:00").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-24T02:00:00Z"));
        }

        @Test
        void fridayReportingDate_runNumber1_skipsSatSun_givesMonday() {
            // reportingDate=Fri 2026-02-20, n=1 → T+1 = Mon 2026-02-23
            StartRunRequest request = StartRunRequest.builder()
                    .runId("r").calculatorId("c").calculatorName("cal")
                    .frequency(Frequency.DAILY)
                    .reportingDate(LocalDate.of(2026, 2, 20))  // Friday
                    .startTime(START)
                    .slaTime("09:30").runNumber("1").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-23T09:30:00Z"));
        }

        @Test
        void lateNightStart_noFalseBreach() {
            // START at 23:55Z — with old logic this would give a deadline in the past.
            // New logic: deadline is Tue 2026-02-24T22:00Z regardless of startTime.
            StartRunRequest request = StartRunRequest.builder()
                    .runId("r").calculatorId("c").calculatorName("cal")
                    .frequency(Frequency.DAILY)
                    .reportingDate(LocalDate.of(2026, 2, 22))
                    .startTime(Instant.parse("2026-02-22T23:55:00Z"))
                    .slaTime("22:00").build();  // default n=2

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            // Must be in the future relative to the startTime (Tue 24 >> Sun 22 23:55)
            assertThat(result.deadline()).isEqualTo(Instant.parse("2026-02-24T22:00:00Z"));
            assertThat(result.deadline()).isAfter(Instant.parse("2026-02-22T23:55:00Z"));
        }

        @Test
        void blankSlaTime_returnsUngraded() {
            StartRunRequest request = baseRequest().slaTime("").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isNull();
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void nullSlaTime_returnsUngraded() {
            SlaBaselineResolver.SlaResolution result = resolver.resolve(baseRequest().build(), Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isNull();
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void malformedClockString_throwsValidationException() {
            StartRunRequest request = baseRequest().slaTime("not-a-time").build();

            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(request, Frequency.DAILY, EMPTY));
        }

        @Test
        void isoDurationRejectedInClockTimeMode() {
            // PT2H30M is a duration — invalid as a clock time
            StartRunRequest request = baseRequest().slaTime("PT2H30M").build();

            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(request, Frequency.DAILY, EMPTY));
        }

        @Test
        void legacyInstantRejectedInClockTimeMode() {
            Instant legacyDeadline = START.plusSeconds(30 * 60);
            StartRunRequest request = baseRequest().slaTime(legacyDeadline.toString()).build();

            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(request, Frequency.DAILY, EMPTY));
        }

        @Test
        void nullStartTime_returnsUngraded() {
            StartRunRequest request = baseRequest().startTime(null).slaTime("22:00").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isNull();
            assertThat(result.baselineDurationMs()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // DURATION mode (legacy / phase-2 path)
    // -----------------------------------------------------------------------

    @Nested
    class DurationMode {

        @BeforeEach
        void setDurationMode() {
            slaProperties.setMode(SlaMode.DURATION);
        }

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
        void noBaselineAtAll_returnsUngradedResolution() {
            SlaBaselineResolver.SlaResolution result = resolver.resolve(baseRequest().build(), Frequency.DAILY, EMPTY);

            assertThat(result.deadline()).isNull();
            assertThat(result.baselineDurationMs()).isNull();
        }

        @Test
        void disabled_returnsUngraded() {
            durationProps.setEnabled(false);
            StartRunRequest request = baseRequest().slaTime("PT45M").build();

            SlaBaselineResolver.SlaResolution result = resolver.resolve(request, Frequency.DAILY, profile(600_000L, 10));

            assertThat(result.deadline()).isNull();
            assertThat(result.baselineDurationMs()).isNull();
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
        void clockTimeRejectedInDurationMode() {
            StartRunRequest request = baseRequest().slaTime("22:00").build();

            assertThrows(DomainValidationException.class,
                    () -> resolver.resolve(request, Frequency.DAILY, EMPTY));
        }
    }
}
