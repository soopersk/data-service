package com.company.observability.domain.enums;

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

    public static CalculatorFrequency fromString(String frequency) {
        if (frequency == null) {
            return DAILY;
        }
        try {
            return CalculatorFrequency.valueOf(frequency.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DAILY;
        }
    }
}