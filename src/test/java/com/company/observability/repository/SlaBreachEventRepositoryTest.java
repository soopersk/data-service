package com.company.observability.repository;

import com.company.observability.domain.SlaBreachEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaBreachEventRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SlaBreachEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SlaBreachEventRepository(jdbcTemplate);
    }

    @Test
    void findByCalculatorIdPaginated_withSeverity_usesLimitOffsetOrdering() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.findByCalculatorIdPaginated("calc-1", "tenant-1", 30, "HIGH", 40, 20);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("AND severity = ?"));
        assertTrue(sql.contains("ORDER BY created_at DESC"));
        assertTrue(sql.contains("LIMIT ? OFFSET ?"));
    }

    @Test
    void findByCalculatorIdKeyset_withCursorAndSeverity_usesStableKeysetPredicateAndOrder() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.findByCalculatorIdKeyset(
                "calc-1", "tenant-1", 30, "CRITICAL",
                Instant.parse("2026-02-22T10:00:00Z"), 123L, 25
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("AND severity = ?"));
        assertTrue(sql.contains("AND (created_at, breach_id) < (?, ?)"));
        assertTrue(sql.contains("ORDER BY created_at DESC, breach_id DESC"));
        assertTrue(sql.contains("LIMIT ?"));
    }
}
