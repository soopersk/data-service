package com.company.observability.repository;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.util.JsonbConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.company.observability.domain.enums.RunStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Import(CalculatorRunRepository.class)
class CalculatorRunRepositoryJdbcTest extends PostgresJdbcIntegrationTestBase {

    @Autowired
    private CalculatorRunRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class TestBeans {
        @Bean
        RedisCalculatorCache redisCalculatorCache() {
            return Mockito.mock(RedisCalculatorCache.class);
        }

        @Bean
        JsonbConverter jsonbConverter() {
            return Mockito.mock(JsonbConverter.class);
        }
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("TRUNCATE TABLE sla_breach_events RESTART IDENTITY");
        jdbcTemplate.update("TRUNCATE TABLE calculator_runs CASCADE");
    }

    @Test
    void findBatchRecentRunsDbOnly_limitsRowsPerCalculator_usingWindowedQuery() {
        LocalDate reportDate = LocalDate.now();
        Instant base = Instant.parse("2026-02-22T10:00:00Z");

        insertRun("run-c1-1", "calc-1", reportDate, base.plusSeconds(300));
        insertRun("run-c1-2", "calc-1", reportDate, base.plusSeconds(200));
        insertRun("run-c1-3", "calc-1", reportDate, base.plusSeconds(100));
        insertRun("run-c2-1", "calc-2", reportDate, base.plusSeconds(250));
        insertRun("run-c2-2", "calc-2", reportDate, base.plusSeconds(150));

        Map<String, List<CalculatorRun>> result = repository.findBatchRecentRunsDbOnly(
                List.of("calc-1", "calc-2"), "tenant-1", CalculatorFrequency.DAILY, 2
        );

        assertEquals(2, result.get("calc-1").size());
        assertEquals(2, result.get("calc-2").size());
        assertEquals("run-c1-1", result.get("calc-1").get(0).getRunId());
        assertEquals("run-c1-2", result.get("calc-1").get(1).getRunId());
    }

    @Test
    void findRunsWithSlaStatus_returnsJoinedSeverityFromBreachEvents() {
        LocalDate reportDate = LocalDate.now();
        Instant createdAt = Instant.parse("2026-02-22T12:00:00Z");
        insertRun("run-severity", "calc-1", reportDate, createdAt);

        jdbcTemplate.update("""
            INSERT INTO sla_breach_events (
                run_id, calculator_id, calculator_name, tenant_id,
                breach_type, expected_value, actual_value, severity,
                alerted, alert_status, retry_count, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                "run-severity", "calc-1", "Calculator 1", "tenant-1",
                "TIME_EXCEEDED", 100L, 300L, "HIGH",
                false, "PENDING", 0, Timestamp.from(createdAt)
        );

        List<RunWithSlaStatus> result = repository.findRunsWithSlaStatus(
                "calc-1", "tenant-1", CalculatorFrequency.DAILY, 3
        );

        assertEquals(1, result.size());
        assertEquals("run-severity", result.get(0).runId());
        assertEquals("HIGH", result.get(0).severity() != null ? result.get(0).severity().name() : null);
        assertNotNull(result.get(0).reportingDate());
    }

    // ---------------------------------------------------------------
    // upsert — immutable-column protection
    // ---------------------------------------------------------------

    @Test
    void upsert_onConflict_immutableColumnsNotOverwritten() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        Instant originalStart = Instant.parse("2026-04-10T05:00:00Z");

        CalculatorRun first = CalculatorRun.builder()
                .runId("run-immutable")
                .calculatorId("calc-1")
                .calculatorName("Original Name")
                .tenantId("tenant-1")
                .frequency(com.company.observability.domain.enums.CalculatorFrequency.DAILY)
                .reportingDate(date)
                .startTime(originalStart)
                .status(RunStatus.RUNNING)
                .slaBreached(false)
                .createdAt(originalStart)
                .build();
        repository.upsert(first);

        // Second upsert on same PK with attempted mutations to immutable columns
        CalculatorRun second = CalculatorRun.builder()
                .runId("run-immutable")
                .calculatorId("calc-1")
                .calculatorName("CHANGED NAME")        // ON CONFLICT DO UPDATE does NOT include this column
                .tenantId("tenant-1")
                .frequency(com.company.observability.domain.enums.CalculatorFrequency.DAILY)
                .reportingDate(date)
                .startTime(originalStart.plusSeconds(9999)) // ON CONFLICT DO UPDATE does NOT include this column
                .status(RunStatus.SUCCESS)              // mutable — should be updated
                .slaBreached(false)
                .createdAt(originalStart)
                .build();
        repository.upsert(second);

        Optional<CalculatorRun> saved = repository.findById("run-immutable", date);
        assertThat(saved).isPresent();
        assertThat(saved.get().getCalculatorName()).isEqualTo("Original Name");
        assertThat(saved.get().getStartTime()).isEqualTo(originalStart);
        assertThat(saved.get().getStatus()).isEqualTo(RunStatus.SUCCESS); // mutable, updated
    }

    // ---------------------------------------------------------------
    // markSlaBreached — idempotency and status guard
    // ---------------------------------------------------------------

    @Test
    void markSlaBreached_idempotent_secondCallReturnsZero() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        insertRunWithStatus("run-breach", "calc-1", date, start, "RUNNING");

        int firstCall  = repository.markSlaBreached("run-breach", "Exceeded SLA deadline", date);
        int secondCall = repository.markSlaBreached("run-breach", "Exceeded SLA deadline again", date);

        assertThat(firstCall).isEqualTo(1);
        assertThat(secondCall).isZero(); // sla_breached=true → WHERE sla_breached=false fails

        Optional<CalculatorRun> run = repository.findById("run-breach", date);
        assertThat(run).isPresent();
        assertThat(run.get().getSlaBreached()).isTrue();
    }

    @Test
    void markSlaBreached_completedRun_notUpdated() {
        LocalDate date = LocalDate.of(2026, 4, 10);
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        insertRunWithStatus("run-complete", "calc-1", date, start, "SUCCESS");

        int updated = repository.markSlaBreached("run-complete", "Breach reason", date);

        assertThat(updated).isZero(); // WHERE status='RUNNING' excludes completed runs

        Optional<CalculatorRun> run = repository.findById("run-complete", date);
        assertThat(run).isPresent();
        assertThat(run.get().getSlaBreached()).isFalse();
    }

    private void insertRunWithStatus(String runId, String calculatorId,
                                     LocalDate reportingDate, Instant start, String status) {
        jdbcTemplate.update("""
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, status, sla_breached, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                runId, calculatorId, "Calculator 1", "tenant-1", "DAILY", reportingDate,
                Timestamp.from(start), status, false,
                Timestamp.from(start), Timestamp.from(start)
        );
    }

    private void insertRun(String runId, String calculatorId, LocalDate reportingDate, Instant createdAt) {
        jdbcTemplate.update("""
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, end_time, duration_ms, status, sla_breached, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                runId, calculatorId, "Calculator 1", "tenant-1", "DAILY", reportingDate,
                Timestamp.from(createdAt.minusSeconds(60)),
                Timestamp.from(createdAt),
                60000L,
                "SUCCESS",
                false,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }
}
