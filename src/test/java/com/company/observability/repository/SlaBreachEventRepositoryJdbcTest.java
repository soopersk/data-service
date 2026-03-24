package com.company.observability.repository;

import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.BreachType;
import com.company.observability.domain.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(SlaBreachEventRepository.class)
class SlaBreachEventRepositoryJdbcTest extends PostgresJdbcIntegrationTestBase {

    @Autowired
    private SlaBreachEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("TRUNCATE TABLE sla_breach_events RESTART IDENTITY");
    }

    @Test
    void keysetPagination_returnsStableNextPage_withoutOverlap() {
        Instant t1 = Instant.parse("2026-02-22T12:00:00Z");
        Instant t2 = Instant.parse("2026-02-22T11:00:00Z");
        Instant t3 = Instant.parse("2026-02-22T10:00:00Z");

        save("run-1", t1, Severity.HIGH);
        save("run-2", t2, Severity.HIGH);
        save("run-3", t3, Severity.HIGH);

        List<SlaBreachEvent> firstPage = repository.findByCalculatorIdKeyset(
                "calc-1", "tenant-1", 30, "HIGH", null, null, 2
        );
        assertEquals(2, firstPage.size());
        assertEquals("run-1", firstPage.get(0).getRunId());
        assertEquals("run-2", firstPage.get(1).getRunId());

        SlaBreachEvent cursor = firstPage.get(1);
        List<SlaBreachEvent> secondPage = repository.findByCalculatorIdKeyset(
                "calc-1", "tenant-1", 30, "HIGH", cursor.getCreatedAt(), cursor.getBreachId(), 2
        );
        assertEquals(1, secondPage.size());
        assertEquals("run-3", secondPage.get(0).getRunId());
    }

    @Test
    void paginatedAndCount_withSeverityFilter_returnsMatchingRows() {
        save("run-a", Instant.parse("2026-02-22T12:00:00Z"), Severity.CRITICAL);
        save("run-b", Instant.parse("2026-02-22T11:00:00Z"), Severity.CRITICAL);
        save("run-c", Instant.parse("2026-02-22T10:00:00Z"), Severity.LOW);

        List<SlaBreachEvent> page = repository.findByCalculatorIdPaginated(
                "calc-1", "tenant-1", 30, "CRITICAL", 0, 10
        );
        long count = repository.countByCalculatorIdAndPeriod(
                "calc-1", "tenant-1", 30, "CRITICAL"
        );

        assertEquals(2, page.size());
        assertEquals(2L, count);
    }

    private void save(String runId, Instant createdAt, Severity severity) {
        repository.save(SlaBreachEvent.builder()
                .runId(runId)
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .breachType(BreachType.TIME_EXCEEDED)
                .expectedValue(100L)
                .actualValue(200L)
                .severity(severity)
                .alerted(false)
                .alertStatus(AlertStatus.PENDING)
                .retryCount(0)
                .createdAt(createdAt)
                .build());
    }
}
