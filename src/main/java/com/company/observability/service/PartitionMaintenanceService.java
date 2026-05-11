package com.company.observability.service;

import com.company.observability.dto.response.PartitionOperationResponse;
import com.company.observability.dto.response.PartitionOperationResponse.PartitionStat;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.company.observability.util.ObservabilityConstants.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PartitionMaintenanceService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public PartitionOperationResponse createPartitions() {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.getJdbcOperations().execute("SELECT create_calculator_run_partitions()");
            long durationMs = System.currentTimeMillis() - start;
            meterRegistry.counter(PARTITION_CREATE_SUCCESS).increment();
            log.info("event=partition.create outcome=success durationMs={}", durationMs);
            int count = countPartitions();
            return new PartitionOperationResponse("create", durationMs, count, fetchRecentStats(7));
        } catch (Exception e) {
            meterRegistry.counter(PARTITION_CREATE_FAILURE).increment();
            log.error("event=partition.create outcome=failure", e);
            throw e;
        }
    }

    public PartitionOperationResponse dropPartitions() {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.getJdbcOperations().execute("SELECT drop_old_calculator_run_partitions()");
            long durationMs = System.currentTimeMillis() - start;
            meterRegistry.counter(PARTITION_DROP_SUCCESS).increment();
            log.info("event=partition.drop outcome=success durationMs={}", durationMs);
            int count = countPartitions();
            return new PartitionOperationResponse("drop", durationMs, count, fetchRecentStats(7));
        } catch (Exception e) {
            meterRegistry.counter(PARTITION_DROP_FAILURE).increment();
            log.error("event=partition.drop outcome=failure", e);
            throw e;
        }
    }

    public PartitionOperationResponse getStats() {
        long start = System.currentTimeMillis();
        int count = countPartitions();
        List<PartitionStat> stats = fetchRecentStats(30);
        long durationMs = System.currentTimeMillis() - start;
        return new PartitionOperationResponse("stats", durationMs, count, stats);
    }

    private int countPartitions() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_class c " +
                "JOIN pg_inherits i ON c.oid = i.inhrelid " +
                "JOIN pg_class p ON p.oid = i.inhparent " +
                "WHERE p.relname = 'calculator_runs' AND c.relname LIKE 'calculator_runs_%'",
                EmptySqlParameterSource.INSTANCE,
                Integer.class
        );
        return count != null ? count : 0;
    }

    private List<PartitionStat> fetchRecentStats(int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT " + limit,
                EmptySqlParameterSource.INSTANCE
        );
        return rows.stream().map(row -> new PartitionStat(
                (String) row.get("partition_name"),
                toLocalDate(row.get("partition_date")),
                toLong(row.get("row_count")),
                (String) row.get("total_size"),
                toLong(row.get("daily_runs")),
                toLong(row.get("monthly_runs"))
        )).toList();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDate ld) return ld;
        return null;
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
