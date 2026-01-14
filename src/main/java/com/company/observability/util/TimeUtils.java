package com.company.observability.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

public class TimeUtils {

    private static final ZoneId CET_ZONE = ZoneId.of("CET");

    /**
     * CLD approach: Calculate CET hour as decimal (e.g., 14.50 = 2:30 PM)
     */
    public static BigDecimal calculateCetHour(Instant instant) {
        if (instant == null) return null;

        ZonedDateTime cetTime = instant.atZone(CET_ZONE);
        double hour = cetTime.getHour() + (cetTime.getMinute() / 60.0);
        return BigDecimal.valueOf(hour).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * GT Enhancement: Calculate CET as minutes since midnight (0-1439)
     * Used for daily aggregates
     */
    public static Integer calculateCetMinute(Instant instant) {
        if (instant == null) return null;

        ZonedDateTime cetTime = instant.atZone(CET_ZONE);
        return cetTime.getHour() * 60 + cetTime.getMinute();
    }

    /**
     * GT Enhancement: Get CET date for daily aggregation
     */
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

    /**
     * GT Enhancement: Format CET minutes to HH:MM
     */
    public static String formatCetMinute(Integer cetMinute) {
        if (cetMinute == null) return null;

        int hours = cetMinute / 60;
        int minutes = cetMinute % 60;

        return String.format("%02d:%02d", hours, minutes);
    }
}