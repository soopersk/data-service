package com.company.observability.domain.enums;

public enum SlaMode {
    /** SLA is a clock time (HH:mm UTC). Deadline anchored to the run's start date, rolled +1 day if needed. */
    CLOCK_TIME,
    /** SLA is an ISO-8601 duration (e.g. PT2H30M). Deadline = startTime + buffered baseline. */
    DURATION
}
