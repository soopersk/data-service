package com.company.observability.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.time.Duration;

public enum CalculatorFrequency {
    DAILY(2),    // Look back 2 days for DAILY calculators
    MONTHLY(10); // Look back 10 days for MONTHLY calculators

    private final int lookbackDays;

    CalculatorFrequency(int lookbackDays) {
        this.lookbackDays = lookbackDays;
    }

    public int getLookbackDays() {
        return lookbackDays;
    }

    public Duration getLookbackDuration() {
        return Duration.ofDays(lookbackDays);
    }


    /**
     * Accepts D, DAILY, M, MONTHLY (case-insensitive)
     * Normalizes to DAILY / MONTHLY
     * Default is DAILY
     */
    @JsonCreator
    public static CalculatorFrequency from(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return DAILY;
        }
        return switch (frequency.trim().toUpperCase()) {
            case "M", "MONTHLY" -> MONTHLY;
            default -> DAILY;
        };
    }

    /**
     * Strict parsing for query/analytics endpoints. Rejects invalid values with IllegalArgumentException.
     */
    public static CalculatorFrequency fromStrict(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            throw new IllegalArgumentException("Frequency is required. Valid values: DAILY, D, MONTHLY, M");
        }
        return switch (frequency.trim().toUpperCase()) {
            case "D", "DAILY" -> DAILY;
            case "M", "MONTHLY" -> MONTHLY;
            default -> throw new IllegalArgumentException(
                    "Unknown frequency: '" + frequency + "'. Valid values: DAILY, D, MONTHLY, M");
        };
    }
}