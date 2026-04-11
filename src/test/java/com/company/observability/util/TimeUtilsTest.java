package com.company.observability.util;

import com.company.observability.domain.enums.CalculatorFrequency;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class TimeUtilsTest {

    // -----------------------------------------------------------------------
    // calculateSlaDeadline
    // -----------------------------------------------------------------------

    @Nested
    class CalculateSlaDeadline {

        @Test
        void winter_cetIsUtcPlusOne() {
            // 2026-02-06 CET is UTC+1 → 06:15 CET = 05:15 UTC
            Instant result = TimeUtils.calculateSlaDeadline(
                    LocalDate.of(2026, 2, 6), LocalTime.of(6, 15));

            assertEquals(Instant.parse("2026-02-06T05:15:00Z"), result);
        }

        @Test
        void summer_cestIsUtcPlusTwo() {
            // 2026-07-15 CEST is UTC+2 → 06:30 CEST = 04:30 UTC
            Instant result = TimeUtils.calculateSlaDeadline(
                    LocalDate.of(2026, 7, 15), LocalTime.of(6, 30));

            assertEquals(Instant.parse("2026-07-15T04:30:00Z"), result);
        }

        @Test
        void dstSpringForward_lastSundayMarch() {
            // 2026-03-29: clocks spring forward at 02:00 → CET becomes CEST (UTC+2)
            // 06:15 on that date is already CEST: 06:15 - 2h = 04:15 UTC
            Instant result = TimeUtils.calculateSlaDeadline(
                    LocalDate.of(2026, 3, 29), LocalTime.of(6, 15));

            assertEquals(Instant.parse("2026-03-29T04:15:00Z"), result);
        }

        @Test
        void dstFallBack_lastSundayOctober() {
            // 2026-10-25: clocks fall back at 03:00 CEST → back to CET (UTC+1)
            // 06:15 on that date is CET: 06:15 - 1h = 05:15 UTC
            Instant result = TimeUtils.calculateSlaDeadline(
                    LocalDate.of(2026, 10, 25), LocalTime.of(6, 15));

            assertEquals(Instant.parse("2026-10-25T05:15:00Z"), result);
        }

        @Test
        void nullReportingDate_returnsNull() {
            assertNull(TimeUtils.calculateSlaDeadline(null, LocalTime.of(6, 0)));
        }

        @Test
        void nullSlaTime_returnsNull() {
            assertNull(TimeUtils.calculateSlaDeadline(LocalDate.of(2026, 2, 6), null));
        }

        @Test
        void bothNull_returnsNull() {
            assertNull(TimeUtils.calculateSlaDeadline(null, null));
        }
    }

    // -----------------------------------------------------------------------
    // calculateEstimatedEndTime
    // -----------------------------------------------------------------------

    @Nested
    class CalculateEstimatedEndTime {

        @Test
        void addsMillisToStartTime() {
            Instant start = Instant.parse("2026-02-06T04:00:00Z");
            Instant result = TimeUtils.calculateEstimatedEndTime(start, 300_000L); // 5 min

            assertEquals(Instant.parse("2026-02-06T04:05:00Z"), result);
        }

        @Test
        void zeroDuration_returnsSameInstant() {
            Instant start = Instant.parse("2026-02-06T04:00:00Z");
            assertEquals(start, TimeUtils.calculateEstimatedEndTime(start, 0L));
        }

        @Test
        void nullStartTime_returnsNull() {
            assertNull(TimeUtils.calculateEstimatedEndTime(null, 1000L));
        }

        @Test
        void nullDuration_returnsNull() {
            assertNull(TimeUtils.calculateEstimatedEndTime(Instant.now(), null));
        }
    }

    // -----------------------------------------------------------------------
    // calculateNextEstimatedStart
    // -----------------------------------------------------------------------

    @Nested
    class CalculateNextEstimatedStart {

        @Test
        void daily_advancesOneDay() {
            // reporting date 2026-02-06, next day = 2026-02-07
            // 04:15 CET (winter, UTC+1) = 03:15 UTC
            Instant result = TimeUtils.calculateNextEstimatedStart(
                    LocalDate.of(2026, 2, 6), LocalTime.of(4, 15), CalculatorFrequency.DAILY);

            assertEquals(Instant.parse("2026-02-07T03:15:00Z"), result);
        }

        @Test
        void monthly_advancesToEndOfNextMonth() {
            // reporting date 2026-01-31, next end-of-month = 2026-02-28
            // 04:00 CET (winter) = 03:00 UTC
            Instant result = TimeUtils.calculateNextEstimatedStart(
                    LocalDate.of(2026, 1, 31), LocalTime.of(4, 0), CalculatorFrequency.MONTHLY);

            assertEquals(Instant.parse("2026-02-28T03:00:00Z"), result);
        }

        @Test
        void monthly_leapYear_endOfFebruaryIs29th() {
            // 2024 is a leap year: 2024-01-31 → 2024-02-29
            Instant result = TimeUtils.calculateNextEstimatedStart(
                    LocalDate.of(2024, 1, 31), LocalTime.of(4, 0), CalculatorFrequency.MONTHLY);

            assertEquals(Instant.parse("2024-02-29T03:00:00Z"), result);
        }

        @Test
        void daily_crossingDstBoundary_correctUtcOffset() {
            // Day before DST spring-forward: next day is 2026-03-29 (CEST, UTC+2)
            // 04:15 on 2026-03-29 CEST = 02:15 UTC
            Instant result = TimeUtils.calculateNextEstimatedStart(
                    LocalDate.of(2026, 3, 28), LocalTime.of(4, 15), CalculatorFrequency.DAILY);

            assertEquals(Instant.parse("2026-03-29T02:15:00Z"), result);
        }

        @Test
        void nullReportingDate_returnsNull() {
            assertNull(TimeUtils.calculateNextEstimatedStart(
                    null, LocalTime.of(4, 0), CalculatorFrequency.DAILY));
        }

        @Test
        void nullEstimatedStartTime_returnsNull() {
            assertNull(TimeUtils.calculateNextEstimatedStart(
                    LocalDate.of(2026, 2, 6), null, CalculatorFrequency.DAILY));
        }
    }

    // -----------------------------------------------------------------------
    // calculateCetHour
    // -----------------------------------------------------------------------

    @Nested
    class CalculateCetHour {

        @Test
        void exactQuarterHour_winter() {
            // 05:15 UTC = 06:15 CET → 6.25
            Instant instant = Instant.parse("2026-02-06T05:15:00Z");
            assertEquals(new BigDecimal("6.25"), TimeUtils.calculateCetHour(instant));
        }

        @Test
        void exactQuarterHour_summer() {
            // 04:15 UTC = 06:15 CEST → 6.25
            Instant instant = Instant.parse("2026-07-15T04:15:00Z");
            assertEquals(new BigDecimal("6.25"), TimeUtils.calculateCetHour(instant));
        }

        @Test
        void includesSeconds_in_precision() {
            // 05:15:30 UTC = 06:15:30 CET = 6 + (15*60+30)/3600 = 6 + 930/3600 = 6.258333... → 6.26
            Instant instant = Instant.parse("2026-02-06T05:15:30Z");
            assertEquals(new BigDecimal("6.26"), TimeUtils.calculateCetHour(instant));
        }

        @Test
        void midnight() {
            // 23:00 UTC = 00:00 CET (UTC+1) → 0.00
            Instant instant = Instant.parse("2026-02-06T23:00:00Z");
            assertEquals(new BigDecimal("0.00"), TimeUtils.calculateCetHour(instant));
        }

        @Test
        void endOfDay_23h59m() {
            // 22:59 UTC = 23:59 CET → 23 + 59/60 = 23.98333... → 23.98
            Instant instant = Instant.parse("2026-02-06T22:59:00Z");
            assertEquals(new BigDecimal("23.98"), TimeUtils.calculateCetHour(instant));
        }

        @ParameterizedTest(name = "utc={0} → cetHour={1}")
        @CsvSource({
            "2026-02-06T04:00:00Z, 5.00",   // 05:00 CET
            "2026-02-06T04:20:00Z, 5.33",   // 05:20 CET → 5.333 → 5.33
            "2026-02-06T04:30:00Z, 5.50",   // 05:30 CET
            "2026-02-06T04:40:00Z, 5.67",   // 05:40 CET → 5.666 → 5.67
        })
        void roundingVariants_winter(String utcStr, String expected) {
            Instant instant = Instant.parse(utcStr);
            assertEquals(new BigDecimal(expected), TimeUtils.calculateCetHour(instant));
        }

        @Test
        void null_returnsNull() {
            assertNull(TimeUtils.calculateCetHour(null));
        }
    }

    // -----------------------------------------------------------------------
    // calculateCetMinute
    // -----------------------------------------------------------------------

    @Nested
    class CalculateCetMinute {

        @Test
        void midnight_returnsZero() {
            // 23:00 UTC = 00:00 CET → 0 minutes
            Instant instant = Instant.parse("2026-02-06T23:00:00Z");
            assertEquals(0, TimeUtils.calculateCetMinute(instant));
        }

        @Test
        void sixFifteen_cet() {
            // 05:15 UTC = 06:15 CET → 6*60+15 = 375
            Instant instant = Instant.parse("2026-02-06T05:15:00Z");
            assertEquals(375, TimeUtils.calculateCetMinute(instant));
        }

        @Test
        void subMinuteSeconds_truncated() {
            // 05:15:45 UTC = 06:15:45 CET → (6*3600 + 15*60 + 45) / 60 = 22545 / 60 = 375 (integer div)
            Instant instant = Instant.parse("2026-02-06T05:15:45Z");
            assertEquals(375, TimeUtils.calculateCetMinute(instant));
        }

        @Test
        void summer_usesCest() {
            // 04:00 UTC = 06:00 CEST → 6*60 = 360
            Instant instant = Instant.parse("2026-07-15T04:00:00Z");
            assertEquals(360, TimeUtils.calculateCetMinute(instant));
        }

        @Test
        void null_returnsNull() {
            assertNull(TimeUtils.calculateCetMinute(null));
        }
    }

    // -----------------------------------------------------------------------
    // getCetDate
    // -----------------------------------------------------------------------

    @Nested
    class GetCetDate {

        @Test
        void beforeMidnightCet_returnsSameDate() {
            // 2026-02-06T22:00:00Z = 2026-02-06T23:00 CET → still Feb 6
            Instant instant = Instant.parse("2026-02-06T22:00:00Z");
            assertEquals(LocalDate.of(2026, 2, 6), TimeUtils.getCetDate(instant));
        }

        @Test
        void afterMidnightCet_returnsNextDate() {
            // 2026-02-06T23:30:00Z = 2026-02-07T00:30 CET → Feb 7
            Instant instant = Instant.parse("2026-02-06T23:30:00Z");
            assertEquals(LocalDate.of(2026, 2, 7), TimeUtils.getCetDate(instant));
        }

        @Test
        void exactlyMidnightCet_winter() {
            // 2026-02-07T23:00:00Z = 2026-02-08T00:00 CET → Feb 8
            Instant instant = Instant.parse("2026-02-07T23:00:00Z");
            assertEquals(LocalDate.of(2026, 2, 8), TimeUtils.getCetDate(instant));
        }

        @Test
        void summer_usesCestOffset() {
            // 2026-07-15T21:59:00Z = 2026-07-15T23:59 CEST → still Jul 15
            Instant instant = Instant.parse("2026-07-15T21:59:00Z");
            assertEquals(LocalDate.of(2026, 7, 15), TimeUtils.getCetDate(instant));
        }

        @Test
        void null_returnsNull() {
            assertNull(TimeUtils.getCetDate(null));
        }
    }

    // -----------------------------------------------------------------------
    // formatDuration
    // -----------------------------------------------------------------------

    @Nested
    class FormatDuration {

        @Test
        void hoursAndMinutes() {
            // 2hr 15min = 8100000ms
            assertEquals("2hrs 15mins", TimeUtils.formatDuration(8_100_000L));
        }

        @Test
        void exactHours_zeroMinutes() {
            assertEquals("1hrs 0mins", TimeUtils.formatDuration(3_600_000L));
        }

        @Test
        void minutesAndSeconds() {
            // 45min 30s = 2730000ms
            assertEquals("45mins 30s", TimeUtils.formatDuration(2_730_000L));
        }

        @Test
        void exactMinutes_zeroSeconds() {
            assertEquals("5mins 0s", TimeUtils.formatDuration(300_000L));
        }

        @Test
        void secondsOnly() {
            assertEquals("15s", TimeUtils.formatDuration(15_000L));
        }

        @Test
        void lessThanOneSecond_showsZeroSeconds() {
            assertEquals("0s", TimeUtils.formatDuration(500L));
        }

        @Test
        void zero_returnsZeroSeconds() {
            assertEquals("0s", TimeUtils.formatDuration(0L));
        }

        @Test
        void largeDuration_multipleHours() {
            // 10h 5min 3s — only hours and minutes shown when hours > 0
            assertEquals("10hrs 5mins", TimeUtils.formatDuration(36_303_000L));
        }

        @Test
        void null_returnsNull() {
            assertNull(TimeUtils.formatDuration(null));
        }
    }

    // -----------------------------------------------------------------------
    // formatCetHour
    // -----------------------------------------------------------------------

    @Nested
    class FormatCetHour {

        @Test
        void quarterPast_6() {
            // 6.25 → "06:15"
            assertEquals("06:15", TimeUtils.formatCetHour(new BigDecimal("6.25")));
        }

        @Test
        void halfPast_6() {
            // 6.50 → "06:30"
            assertEquals("06:30", TimeUtils.formatCetHour(new BigDecimal("6.50")));
        }

        @Test
        void midnight() {
            // 0.00 → "00:00"
            assertEquals("00:00", TimeUtils.formatCetHour(new BigDecimal("0.00")));
        }

        @Test
        void twentyThirdHour() {
            // 23.98 → totalSeconds=86328 → hour=23, minute=58 (86328%3600=1128, 1128/60=18? Let me verify)
            // 23.98 * 3600 = 86328s → 86328/3600=23, 86328%3600=1128, 1128/60=18 → "23:18"
            // Actually: 23.98 → 23h 58.8min → stored as 23.98
            // 23.98 * 3600 = 86328; 86328 / 3600 = 23 remainder 1128; 1128 / 60 = 18 → "23:18"
            // Wait — let's compute: 0.98 * 60 = 58.8 minutes
            // But the BigDecimal is 23.98 which came from secondsOfDay/3600 with scale 2
            // 23h 58min 48s → secondsOfDay = 23*3600+58*60+48 = 86328 → /3600 = 23.98 (86328/3600=23.9800)
            // formatCetHour(23.98): 23.98*3600=86328, /3600=23, %3600=1128, /60=18 → "23:18"? That's wrong.
            // Let me recalculate: 23*3600=82800; 82800+58*60=82800+3480=86280; 86280+48=86328
            // 86328/3600 = 23 remainder 1128; 1128/60 = 18 remainder 48 → "23:18"
            // Hmm, that means 23.98 rounds down to 23:18, not 23:58.
            // The issue is 23.98 doesn't represent 23h58min — it represents 23 + 98/100 hours = 23h 58.8min
            // But via seconds: 23.98 * 3600 = 86328s; hour=23, remaining=1128s=18min48s → "23:18"
            // Actually 0.98 hours = 0.98*3600 = 3528 seconds = 58min48s
            // But my formula: 86328 % 3600 = 86328 - 23*3600 = 86328 - 82800 = 3528; 3528/60 = 58 ✓
            // Let me recheck: 23*3600 = 82800; 86328 - 82800 = 3528; 3528/60 = 58 → "23:58" ✓
            // I made an arithmetic error above. It's correct.
            assertEquals("23:58", TimeUtils.formatCetHour(new BigDecimal("23.98")));
        }

        @Test
        void oneThird_roundsCorrectly() {
            // 5.33 = 05:19.8min → totalSeconds = 5.33*3600 = 19188; hour=5, 19188%3600=1188, 1188/60=19 → "05:19"
            assertEquals("05:19", TimeUtils.formatCetHour(new BigDecimal("5.33")));
        }

        @Test
        void twoThirds_roundsCorrectly() {
            // 5.67 = 5*3600+0.67*3600 = 18000+2412=20412; %3600=2412; /60=40 → "05:40"
            assertEquals("05:40", TimeUtils.formatCetHour(new BigDecimal("5.67")));
        }

        @Test
        void roundtripWithCalculateCetHour() {
            // 06:15 CET → 6.25 (exact in decimal) → "06:15" — perfect round-trip
            // Note: non-quarter-hour minutes (e.g. 06:20 → 6.33) do NOT round-trip exactly
            // because scale-2 BigDecimal has ~36-second resolution (1/100 of an hour).
            Instant instant = Instant.parse("2026-02-06T05:15:00Z"); // 06:15 CET
            BigDecimal cetHour = TimeUtils.calculateCetHour(instant);
            assertEquals("06:15", TimeUtils.formatCetHour(cetHour));
        }

        @Test
        void precisionLoss_nonQuarterHour() {
            // 06:20 CET: secondsOfDay=22800; 22800/3600 = 6.3333 → rounded to 6.33
            // formatCetHour(6.33): 6.33*3600=22788s; 22788%3600=1188; 1188/60=19 → "06:19"
            // This is expected precision loss — scale-2 BigDecimal cannot represent 20min exactly.
            Instant instant = Instant.parse("2026-02-06T05:20:00Z"); // 06:20 CET
            BigDecimal cetHour = TimeUtils.calculateCetHour(instant);
            assertEquals("06:19", TimeUtils.formatCetHour(cetHour));
        }

        @Test
        void roundtripWithCalculateCetHour_withSeconds() {
            // 06:15:30 CET — seconds contribute to the BigDecimal; formatted result drops seconds (minute precision)
            Instant instant = Instant.parse("2026-02-06T05:15:30Z"); // 06:15:30 CET
            BigDecimal cetHour = TimeUtils.calculateCetHour(instant); // 6.26
            // 6.26 * 3600 = 22536; /3600=6, %3600=936, /60=15 → "06:15"
            // Note: sub-minute rounding in calculateCetHour (30s → rounds 6.25 up to 6.26)
            // then formatCetHour brings it back to 06:15 (936/60=15.6 → integer=15)
            assertEquals("06:15", TimeUtils.formatCetHour(cetHour));
        }

        @Test
        void null_returnsNull() {
            assertNull(TimeUtils.formatCetHour(null));
        }
    }

    // -----------------------------------------------------------------------
    // formatCetMinute
    // -----------------------------------------------------------------------

    @Nested
    class FormatCetMinute {

        @Test
        void zero_returnsMidnight() {
            assertEquals("00:00", TimeUtils.formatCetMinute(0));
        }

        @Test
        void sixFifteen() {
            // 375 min = 6h 15min
            assertEquals("06:15", TimeUtils.formatCetMinute(375));
        }

        @Test
        void endOfDay() {
            // 1439 min = 23h 59min
            assertEquals("23:59", TimeUtils.formatCetMinute(1439));
        }

        @Test
        void exactHour() {
            assertEquals("05:00", TimeUtils.formatCetMinute(300));
        }

        @Test
        void roundtripWithCalculateCetMinute() {
            Instant instant = Instant.parse("2026-02-06T05:15:00Z"); // 06:15 CET
            Integer cetMinute = TimeUtils.calculateCetMinute(instant);
            assertEquals("06:15", TimeUtils.formatCetMinute(cetMinute));
        }

        @Test
        void null_returnsNull() {
            assertNull(TimeUtils.formatCetMinute(null));
        }
    }

    // -----------------------------------------------------------------------
    // toTimestamp
    // -----------------------------------------------------------------------

    @Nested
    class ToTimestamp {

        @Test
        void convertsInstantToTimestamp() {
            Instant instant = Instant.parse("2026-02-06T05:15:00Z");
            Timestamp ts = TimeUtils.toTimestamp(instant);
            assertNotNull(ts);
            assertEquals(instant.toEpochMilli(), ts.getTime());
        }

        @Test
        void null_returnsNull() {
            assertNull(TimeUtils.toTimestamp(null));
        }
    }

    // -----------------------------------------------------------------------
    // fromTimestamp
    // -----------------------------------------------------------------------

    @Nested
    class FromTimestamp {

        @Test
        void convertsTimestampToInstant() {
            Instant expected = Instant.parse("2026-02-06T05:15:00Z");
            Timestamp ts = Timestamp.from(expected);
            assertEquals(expected, TimeUtils.fromTimestamp(ts));
        }

        @Test
        void null_returnsNull() {
            assertNull(TimeUtils.fromTimestamp(null));
        }
    }
}
