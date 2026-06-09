package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public holidays to skip when computing next-business-day SLA deadlines.
 * Bound to {@code observability.business-calendar.*}.
 */
@Component
@ConfigurationProperties(prefix = "observability.business-calendar")
@Getter
@Setter
public class BusinessCalendarProperties {

    /**
     * List of public holiday dates in {@code YYYY-MM-DD} format.
     * Loaded from application.yml; convert to Set on first access for O(1) lookup.
     */
    private List<String> holidays = List.of();

    /** Returns the configured holidays as a {@link LocalDate} set. */
    public Set<LocalDate> holidayDates() {
        return holidays.stream()
                .map(LocalDate::parse)
                .collect(Collectors.toSet());
    }
}
