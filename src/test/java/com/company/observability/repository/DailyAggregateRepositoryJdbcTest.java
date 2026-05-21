package com.company.observability.repository;

import com.company.observability.domain.CalculatorProfile;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    // Reporting dates must fall inside the partition range created by V2 (yesterday .. +60d).
    private static final LocalDate DATE = LocalDate.now();

    @BeforeEach
    void clean() {
        jdbcTemplate.update("TRUNCATE TABLE calculator_sli_daily");
        jdbcTemplate.update("TRUNCATE TABLE calculator_runs");
    }

    /** Insert one completed run into the partitioned source table. */
    private void insertRun(String runId, String calcId, String tenant, String frequency,
                           LocalDate reportingDate, int startMinUtc, long durationMs,
                           String status, boolean slaBreached) {
        OffsetDateTime start = reportingDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().plusMinutes(startMinUtc);
        OffsetDateTime end = start.plusNanos(durationMs * 1_000_000L);
        jdbcTemplate.update("""
                INSERT INTO calculator_runs (
                    run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                    start_time, end_time, duration_ms, status, sla_breached)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId, calcId, calcId, tenant, frequency, reportingDate,
                start, end, durationMs, status, slaBreached);
    }

    // ---------------------------------------------------------------
    // recomputeForDateRange — build aggregate from source runs
    // ---------------------------------------------------------------

    @Test
    void recompute_buildsAggregateFromRuns() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("r2", "calc-1", "tenant-1", "DAILY", DATE, 360, 200L, "SUCCESS", false);

        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", "tenant-1", 3);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).totalRuns()).isEqualTo(2);
        assertThat(results.get(0).sumDurationMs()).isEqualTo(300L);
        assertThat(results.get(0).avgDurationMs()).isEqualTo(150L);
    }

    @Test
    void recompute_isIdempotent() {
        insertRun("r1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);

        repository.recomputeForDateRange(DATE.minusDays(1), DATE);
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", "tenant-1", 3);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).totalRuns()).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Frequency dimension — DAILY and MONTHLY stay separate
    // ---------------------------------------------------------------

    @Test
    void findProfile_separatesByFrequency_evenOnSharedDate() {
        insertRun("d1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("m1", "calc-1", "tenant-1", "MONTHLY", DATE, 300, 500L, "SUCCESS", false);
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

        CalculatorProfile daily = repository.findProfile("calc-1", "tenant-1", "DAILY", 3);
        CalculatorProfile monthly = repository.findProfile("calc-1", "tenant-1", "MONTHLY", 3);

        assertThat(daily.totalRuns()).isEqualTo(1);
        assertThat(daily.avgDurationMs()).isEqualTo(100L);
        assertThat(monthly.totalRuns()).isEqualTo(1);
        assertThat(monthly.avgDurationMs()).isEqualTo(500L);
    }

    @Test
    void findRecentAggregates_collapsesAcrossFrequency() {
        insertRun("d1", "calc-1", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("m1", "calc-1", "tenant-1", "MONTHLY", DATE, 300, 500L, "SUCCESS", false);
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

        List<DailyAggregate> results = repository.findRecentAggregates("calc-1", "tenant-1", 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).totalRuns()).isEqualTo(2);
        assertThat(results.get(0).sumDurationMs()).isEqualTo(600L);
    }

    @Test
    void findAllProfiles_returnsOneProfilePerCalculatorForFrequency() {
        insertRun("a1", "calc-A", "tenant-1", "DAILY", DATE, 300, 100L, "SUCCESS", false);
        insertRun("a2", "calc-A", "tenant-1", "DAILY", DATE, 360, 300L, "SUCCESS", false);
        insertRun("b1", "calc-B", "tenant-1", "DAILY", DATE, 300, 50L, "SUCCESS", false);
        repository.recomputeForDateRange(DATE.minusDays(1), DATE);

        List<CalculatorProfile> profiles = repository.findAllProfiles("DAILY", 3);

        assertThat(profiles).hasSize(2);
        assertThat(profiles).extracting(CalculatorProfile::calculatorId)
                .containsExactlyInAnyOrder("calc-A", "calc-B");
    }

    @Test
    void findProfile_noRows_returnsZeroSampleProfile() {
        CalculatorProfile result = repository.findProfile("missing", "tenant-1", "DAILY", 30);

        assertThat(result.totalRuns()).isZero();
        assertThat(result.avgDurationMs()).isZero();
    }
}
