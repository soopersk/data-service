package com.company.observability.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

public class TimeUtils {

    private static final ZoneId CET_ZONE = ZoneId.of("CET");

    /**
     * Calculate absolute SLA deadline time from start time and SLA time of day
     *
     * @param startTime When the run started (UTC)
     * @param slaTimeCet Target completion time in CET (e.g., 06:15:00)
     * @return Absolute deadline time in UTC
     */
    public static Instant calculateSlaDeadline(Instant startTime, LocalTime slaTimeCet) {
        if (startTime == null || slaTimeCet == null) return null;

        // Convert start time to CET date
        ZonedDateTime startCet = startTime.atZone(CET_ZONE);
        LocalDate startDate = startCet.toLocalDate();

        // Combine with SLA time
        ZonedDateTime slaDateTime = ZonedDateTime.of(startDate, slaTimeCet, CET_ZONE);

        // If SLA time is before start time, it means next day
        if (slaDateTime.isBefore(startCet)) {
            slaDateTime = slaDateTime.plusDays(1);
        }

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
     * Calculate next expected start time for a calculator
     *
     * @param lastRunStart Last run start time
     * @param frequency DAILY or MONTHLY
     * @param estimatedStartTimeCet Estimated start time in CET (e.g., 04:15:00)
     * @return Next estimated start time
     */
    public static Instant calculateNextEstimatedStart(
            Instant lastRunStart, String frequency, LocalTime estimatedStartTimeCet) {

        if (lastRunStart == null || estimatedStartTimeCet == null) return null;

        ZonedDateTime lastStartCet = lastRunStart.atZone(CET_ZONE);
        LocalDate nextDate;

        if ("MONTHLY".equals(frequency)) {
            nextDate = lastStartCet.toLocalDate().plusMonths(1);
        } else {
            nextDate = lastStartCet.toLocalDate().plusDays(1);
        }

        ZonedDateTime nextStart = ZonedDateTime.of(nextDate, estimatedStartTimeCet, CET_ZONE);
        return nextStart.toInstant();
    }

    public static BigDecimal calculateCetHour(Instant instant) {
        if (instant == null) return null;

        ZonedDateTime cetTime = instant.atZone(CET_ZONE);
        double hour = cetTime.getHour() + (cetTime.getMinute() / 60.0);
        return BigDecimal.valueOf(hour).setScale(2, RoundingMode.HALF_UP);
    }

    public static Integer calculateCetMinute(Instant instant) {
        if (instant == null) return null;

        ZonedDateTime cetTime = instant.atZone(CET_ZONE);
        return cetTime.getHour() * 60 + cetTime.getMinute();
    }

    public static LocalDate getCetDate(Instant instant) {
        if (instant == null) return null;
        return instant.atZone(CET_ZONE).toLocalDate();
    }

    public static String formatDuration(Long durationMs) {
        if (durationMs == null) return null;

        long hours = durationMs / 3600000;
        long minutes = (durationMs % 3600000) / 60000;
        long seconds = (durationMs % 60000) / 1000;

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    public static String formatCetHour(BigDecimal hourCet) {
        if (hourCet == null) return null;

        int hour = hourCet.intValue();
        int minute = hourCet.subtract(BigDecimal.valueOf(hour))
                .multiply(BigDecimal.valueOf(60))
                .intValue();

        return String.format("%02d:%02d", hour, minute);
    }

    public static String formatCetMinute(Integer cetMinute) {
        if (cetMinute == null) return null;

        int hours = cetMinute / 60;
        int minutes = cetMinute % 60;

        return String.format("%02d:%02d", hours, minutes);
    }
}