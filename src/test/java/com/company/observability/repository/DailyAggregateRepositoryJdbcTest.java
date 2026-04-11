package com.company.observability.repository;

import com.company.observability.domain.DailyAggregate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(DailyAggregateRepository.class)
class DailyAggregateRepositoryJdbcTest extends PostgresJdbcIntegrationTestBase {

    @TestConfiguration
    static class TestBeans {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private DailyAggregateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("TRUNCATE TABLE calculator_sli_daily");
    }

    // ---------------------------------------------------------------
    // upsertDaily — insert and update
    // ---------------------------------------------------------------

    @Test
    void upsertDaily_insert_createsRowWithCorrectValues() {
        LocalDate date = LocalDate.of(2026, 4, 10);

        repository.upsertDaily("calc-1", "tenant-1", date, "SUCCESS", false, 60000L, 300, 360);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", "tenant-1", 365);

        assertThat(results).hasSize(1);
        DailyAggregate agg = results.get(0);
        assertThat(agg.calculatorId()).isEqualTo("calc-1");
        assertThat(agg.tenantId()).isEqualTo("tenant-1");
        assertThat(agg.dayCet()).isEqualTo(date);
        assertThat(agg.totalRuns()).isEqualTo(1);
        assertThat(agg.successRuns()).isEqualTo(1);
        assertThat(agg.slaBreaches()).isZero();
        assertThat(agg.avgDurationMs()).isEqualTo(60000L);
    }

    @Test
    void upsertDaily_secondUpsert_computesRunningAverage() {
        // TD-3: this running-average formula is NOT concurrency-safe under parallel writes.
        // This test validates the correct sequential (single-writer) behavior only.
        LocalDate date = LocalDate.of(2026, 4, 10);

        repository.upsertDaily("calc-1", "tenant-1", date, "SUCCESS", false, 100L, 300, 360);
        repository.upsertDaily("calc-1", "tenant-1", date, "SUCCESS", false, 200L, 300, 360);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", "tenant-1", 365);

        assertThat(results).hasSize(1);
        DailyAggregate agg = results.get(0);
        assertThat(agg.totalRuns()).isEqualTo(2);
        assertThat(agg.successRuns()).isEqualTo(2);
        // avg = (100 * 1 + 200) / (1 + 1) = 150
        assertThat(agg.avgDurationMs()).isEqualTo(150L);
    }

    // ---------------------------------------------------------------
    // findRecentAggregates — day-window filtering
    // ---------------------------------------------------------------

    @Test
    void findRecentAggregates_filtersByDayWindow() {
        LocalDate today = LocalDate.now();
        // day-1 and day-5 fall within a 7-day window; day-10 is outside it
        repository.upsertDaily("calc-1", "tenant-1", today.minusDays(1), "SUCCESS", false, 1000L, 300, 360);
        repository.upsertDaily("calc-1", "tenant-1", today.minusDays(5), "SUCCESS", false, 2000L, 300, 360);
        repository.upsertDaily("calc-1", "tenant-1", today.minusDays(10), "SUCCESS", false, 3000L, 300, 360);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", "tenant-1", 7);

        assertThat(results).hasSize(2);
        // Results are ordered descending — most recent first
        assertThat(results.get(0).dayCet()).isEqualTo(today.minusDays(1));
        assertThat(results.get(1).dayCet()).isEqualTo(today.minusDays(5));
    }

    // ---------------------------------------------------------------
    // findByReportingDates — empty-input guard
    // ---------------------------------------------------------------

    @Test
    void findByReportingDates_emptyInput_returnsEmptyListWithoutException() {
        // Guard clause in the repository returns early — no SQL issued, no exception thrown
        List<DailyAggregate> result = repository.findByReportingDates(
                "calc-1", "tenant-1", Collections.emptyList());

        assertThat(result).isEmpty();
    }
}
