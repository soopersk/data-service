package com.company.observability.util;

import com.company.observability.domain.enums.CalculatorFrequency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;

public class TimeUtils {

    // "Europe/Amsterdam" correctly handles CET (UTC+1) and CEST (UTC+2, DST).
    // "CET" is a fixed offset (+01:00) and does NOT observe DST — never use it.
    private static final ZoneId CET_ZONE = ZoneId.of("Europe/Amsterdam");

    /**
     * Calculate absolute SLA deadline time from reporting date and SLA time of day (CET)
     *
     * @param reportingDate Reporting date in CET calendar
     * @param slaTimeCet Target completion time in CET (e.g., 06:15:00)
     * @return Absolute deadline time in UTC
     */
    public static Instant calculateSlaDeadline(LocalDate reportingDate, LocalTime slaTimeCet) {
        if (reportingDate == null || slaTimeCet == null) return null;

        ZonedDateTime slaDateTime = ZonedDateTime.of(reportingDate, slaTimeCet, CET_ZONE);
        return slaDateTime.toInstant();
    }

    /**
     * Calculate estimated end time from start time and expected duration
     */
    public static Instant calculateEstimatedEndTime(Instant startTime, Long expectedDurationMs) {
        if (startTime == null || expectedDurationMs == null) return null;
        return startTime.plusMillis(expectedDurationMs);
    }

    /**
     * Calculate next expected start time for a calculator.
     *
     * @param reportingDate The current reporting date (CET calendar date of the run being started)
     * @param estimatedStartTimeCet Estimated start time of day in CET (e.g., 04:15:00)
     * @param frequency DAILY or MONTHLY
     * @return Next estimated start time as a UTC Instant
     */
    public static Instant calculateNextEstimatedStart(
            LocalDate reportingDate, LocalTime estimatedStartTimeCet, CalculatorFrequency frequency) {

        if (reportingDate == null || estimatedStartTimeCet == null) return null;

        LocalDate nextDate;
        if (frequency == CalculatorFrequency.MONTHLY) {
            // Next month's end-of-month date
            nextDate = reportingDate.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
        } else {
            // Next calendar day
            nextDate = reportingDate.plusDays(1);
        }

        return ZonedDateTime.of(nextDate, estimatedStartTimeCet, CET_ZONE).toInstant();
    }

    public static BigDecimal calculateCetHour(Instant instant) {
        if (instant == null) return null;

        int secondsOfDay = instant.atZone(CET_ZONE).toLocalTime().toSecondOfDay();
        return BigDecimal.valueOf(secondsOfDay).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
    }

    public static Integer calculateCetMinute(Instant instant) {
        if (instant == null) return null;

        return instant.atZone(CET_ZONE).toLocalTime().toSecondOfDay() / 60;
    }

    public static LocalDate getCetDate(Instant instant) {
        if (instant == null) return null;
        return instant.atZone(CET_ZONE).toLocalDate();
    }

    public static String formatDuration(Long durationMs) {
        if (durationMs == null) return null;

        long hours = durationMs / 3_600_000;
        long minutes = (durationMs % 3_600_000) / 60_000;
        long seconds = (durationMs % 60_000) / 1_000;

        if (hours > 0) {
            return String.format("%dhrs %dmins", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dmins %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    public static String formatCetHour(BigDecimal hourCet) {
        if (hourCet == null) return null;

        // Convert fractional hours back to total seconds to avoid floating-point rounding
        // e.g. 6.25 → 22500s → hour=6, minute=15
        int totalSeconds = hourCet.multiply(BigDecimal.valueOf(3600))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        int hour = totalSeconds / 3600;
        int minute = (totalSeconds % 3600) / 60;

        return String.format("%02d:%02d", hour, minute);
    }

    public static String formatCetMinute(Integer cetMinute) {
        if (cetMinute == null) return null;

        int hours = cetMinute / 60;
        int minutes = cetMinute % 60;

        return String.format("%02d:%02d", hours, minutes);
    }

    public static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    public static Instant fromTimestamp(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

}
