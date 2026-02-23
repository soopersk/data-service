package com.company.observability.domain.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunStatusTest {

    @Test
    void fromCompletionStatus_defaultsToSuccessWhenOmitted() {
        assertEquals(RunStatus.SUCCESS, RunStatus.fromCompletionStatus(null));
    }

    @Test
    void fromCompletionStatus_rejectsUnknownValues() {
        assertThrows(IllegalArgumentException.class,
                () -> RunStatus.fromCompletionStatus("DONE"));
    }

    @Test
    void fromCompletionStatus_rejectsRunning() {
        assertThrows(IllegalArgumentException.class,
                () -> RunStatus.fromCompletionStatus("RUNNING"));
    }

    @Test
    void fromCompletionStatus_acceptsTerminalValuesCaseInsensitive() {
        assertEquals(RunStatus.FAILED, RunStatus.fromCompletionStatus("failed"));
        assertEquals(RunStatus.TIMEOUT, RunStatus.fromCompletionStatus("TIMEOUT"));
        assertEquals(RunStatus.CANCELLED, RunStatus.fromCompletionStatus("cancelled"));
    }
}
