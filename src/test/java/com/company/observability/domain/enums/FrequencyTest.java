package com.company.observability.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FrequencyTest {

    @ParameterizedTest
    @ValueSource(strings = {"D", "DAILY", "daily", "Daily"})
    void fromStrict_daily(String input) {
        assertEquals(Frequency.DAILY, Frequency.fromStrict(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"M", "MONTHLY", "monthly", "Monthly"})
    void fromStrict_monthly(String input) {
        assertEquals(Frequency.MONTHLY, Frequency.fromStrict(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WEEKLY", "X", "123", ""})
    void fromStrict_invalid_throws(String input) {
        assertThrows(IllegalArgumentException.class, () -> Frequency.fromStrict(input));
    }

    @Test
    void fromStrict_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> Frequency.fromStrict(null));
    }

    @Test
    void from_null_defaults_daily() {
        assertEquals(Frequency.DAILY, Frequency.from(null));
    }
}
