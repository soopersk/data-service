package com.company.observability.service;

import com.company.observability.config.BusinessCalendarProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Stateless business-calendar utilities for deterministic SLA deadline resolution.
 *
 * <p>A SLA deadline is a <em>business fact</em>, not an operational measurement.
 * Anchoring to {@code reportingDate + runNumber} makes the deadline independent of
 * when Airflow happened to trigger a run ({@code startTime}).
 */
@Service
@RequiredArgsConstructor
public class BusinessCalendarService {

    private final BusinessCalendarProperties props;

    /**
     * Advance {@code start} by {@code n} business days, skipping weekends and configured holidays.
     *
     * <ul>
     *   <li>{@code n=1} → T+1 (next business day after {@code start})</li>
     *   <li>{@code n=2} → T+2</li>
     *   <li>{@code n=0} → same day (guard against null/missing run_number)</li>
     * </ul>
     */
    public LocalDate nextBusinessDay(LocalDate start, int n) {
        if (n <= 0) {
            return start;
        }
        Set<LocalDate> holidays = props.holidayDates();
        LocalDate result = start;
        int stepsTaken = 0;
        while (stepsTaken < n) {
            result = result.plusDays(1);
            if (isBusinessDay(result, holidays)) {
                stepsTaken++;
            }
        }
        return result;
    }

    private boolean isBusinessDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
    }
}
