package com.company.observability.repository;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.util.JsonbConverter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculatorRunRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private RedisCalculatorCache redisCache;
    @Mock
    private JsonbConverter jsonbConverter;

    private CalculatorRunRepository repository;

    @BeforeEach
    void setUp() {
        repository = new CalculatorRunRepository(jdbcTemplate, redisCache, jsonbConverter, new SimpleMeterRegistry());
    }

    @Test
    void findBatchRecentRunsDbOnly_skipsRedisReadChecks_andCachesDbResults() {
        CalculatorRun dbRun = run("calc-2", "run-2");
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(dbRun));

        Map<String, List<CalculatorRun>> result = repository.findBatchRecentRunsDbOnly(
                List.of("calc-2"), "tenant-1", CalculatorFrequency.DAILY, 3
        );

        assertEquals(1, result.size());
        assertTrue(result.containsKey("calc-2"));
        verify(redisCache, never()).getRecentRuns(anyString(), anyString(), any(CalculatorFrequency.class), anyInt());
        verify(redisCache).cacheRunOnWrite(dbRun);
    }

    @Test
    void findBatchRecentRuns_usesCacheHits_andQueriesOnlyMisses_withHotPathWindowingSql() {
        CalculatorRun cachedRun = run("calc-1", "run-1");
        CalculatorRun dbRun = run("calc-2", "run-2");

        when(redisCache.getRecentRuns("calc-1", "tenant-1", CalculatorFrequency.DAILY, 3))
                .thenReturn(Optional.of(List.of(cachedRun)));
        when(redisCache.getRecentRuns("calc-2", "tenant-1", CalculatorFrequency.DAILY, 3))
                .thenReturn(Optional.empty());

        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(dbRun));

        Map<String, List<CalculatorRun>> result = repository.findBatchRecentRuns(
                List.of("calc-1", "calc-2"), "tenant-1", CalculatorFrequency.DAILY, 3
        );

        assertEquals(2, result.size());
        assertEquals(1, result.get("calc-1").size());
        assertEquals(1, result.get("calc-2").size());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("ROW_NUMBER() OVER"));
        assertTrue(sql.contains("PARTITION BY calculator_id"));
        assertTrue(sql.contains("rn <= :limit"));
        verify(redisCache).cacheRunOnWrite(dbRun);
    }

    private CalculatorRun run(String calculatorId, String runId) {
        return CalculatorRun.builder()
                .runId(runId)
                .calculatorId(calculatorId)
                .calculatorName("Calculator " + calculatorId)
                .tenantId("tenant-1")
                .frequency(CalculatorFrequency.DAILY)
                .reportingDate(LocalDate.of(2026, 2, 22))
                .startTime(Instant.parse("2026-02-22T08:00:00Z"))
                .endTime(Instant.parse("2026-02-22T08:05:00Z"))
                .durationMs(300000L)
                .status(RunStatus.SUCCESS)
                .slaBreached(false)
                .createdAt(Instant.parse("2026-02-22T08:05:00Z"))
                .updatedAt(Instant.parse("2026-02-22T08:05:00Z"))
                .build();
    }
}
