package com.company.observability.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorFrequencyTest {

    @ParameterizedTest
    @ValueSource(strings = {"D", "DAILY", "daily", "Daily"})
    void fromStrict_daily(String input) {
        assertEquals(CalculatorFrequency.DAILY, CalculatorFrequency.fromStrict(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"M", "MONTHLY", "monthly", "Monthly"})
    void fromStrict_monthly(String input) {
        assertEquals(CalculatorFrequency.MONTHLY, CalculatorFrequency.fromStrict(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WEEKLY", "X", "123", ""})
    void fromStrict_invalid_throws(String input) {
        assertThrows(IllegalArgumentException.class, () -> CalculatorFrequency.fromStrict(input));
    }

    @Test
    void fromStrict_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> CalculatorFrequency.fromStrict(null));
    }

    @Test
    void from_null_defaults_daily() {
        assertEquals(CalculatorFrequency.DAILY, CalculatorFrequency.from(null));
    }
}
