package com.company.observability.scheduled;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Async partition management with monitoring
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.partitions.management.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PartitionManagementJob {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * Create partitions daily at 1 AM
     * Creates partitions for next 60 days
     */
    @Scheduled(cron = "${observability.partitions.management.create-cron:0 0 1 * * *}")
    public void createPartitions() {
        log.info("Starting partition creation job (async)");

        CompletableFuture.runAsync(() -> {
            try {
                jdbcTemplate.execute("SELECT create_calculator_run_partitions()");

                log.info("Successfully created calculator_runs partitions for next 60 days");
                meterRegistry.counter("partitions.create.success").increment();

                // Log partition statistics
                logPartitionStatistics();

            } catch (Exception e) {
                log.error("Failed to create partitions", e);
                meterRegistry.counter("partitions.create.failures").increment();
            }
        });
    }

    /**
     * Drop old partitions weekly on Sunday at 2 AM
     * Drops partitions older than 395 days (13+ months)
     */
    @Scheduled(cron = "${observability.partitions.management.drop-cron:0 0 2 * * SUN}")
    public void dropOldPartitions() {
        log.info("Starting old partition cleanup job (async)");

        CompletableFuture.runAsync(() -> {
            try {
                jdbcTemplate.execute("SELECT drop_old_calculator_run_partitions()");

                log.info("Successfully dropped old calculator_runs partitions");
                meterRegistry.counter("partitions.drop.success").increment();

                // Log remaining partition count
                logPartitionStatistics();

            } catch (Exception e) {
                log.error("Failed to drop old partitions", e);
                meterRegistry.counter("partitions.drop.failures").increment();
            }
        });
    }

    /**
     * Monitor partition health daily at 6 AM
     */
    @Scheduled(cron = "${observability.partitions.monitoring.cron:0 0 6 * * *}")
    public void monitorPartitionHealth() {
        log.info("Running partition health monitoring");

        try {
            List<Map<String, Object>> stats = jdbcTemplate.queryForList(
                    "SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 30"
            );

            long totalRows = 0;
            long dailyRows = 0;
            long monthlyRows = 0;

            for (Map<String, Object> stat : stats) {
                Long rowCount = ((Number) stat.get("row_count")).longValue();
                Long daily = ((Number) stat.get("daily_runs")).longValue();
                Long monthly = ((Number) stat.get("monthly_runs")).longValue();

                totalRows += rowCount;
                dailyRows += daily;
                monthlyRows += monthly;

                log.debug("Partition {}: {} rows ({} DAILY, {} MONTHLY), size: {}",
                        stat.get("partition_name"),
                        rowCount,
                        daily,
                        monthly,
                        stat.get("total_size"));
            }

            // Record metrics
            meterRegistry.gauge("partitions.total_rows", totalRows);
            meterRegistry.gauge("partitions.daily_rows", dailyRows);
            meterRegistry.gauge("partitions.monthly_rows", monthlyRows);
            meterRegistry.gauge("partitions.count", stats.size());

            log.info("Partition health: {} partitions, {} total rows ({} DAILY, {} MONTHLY)",
                    stats.size(), totalRows, dailyRows, monthlyRows);

        } catch (Exception e) {
            log.error("Failed to monitor partition health", e);
            meterRegistry.counter("partitions.monitoring.failures").increment();
        }
    }

    private void logPartitionStatistics() {
        try {
            List<Map<String, Object>> recentStats = jdbcTemplate.queryForList(
                    "SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 7"
            );

            log.info("Recent partition statistics:");
            for (Map<String, Object> stat : recentStats) {
                log.info("  {}: {} rows, {} size",
                        stat.get("partition_name"),
                        stat.get("row_count"),
                        stat.get("total_size"));
            }
        } catch (Exception e) {
            log.warn("Failed to log partition statistics", e);
        }
    }
}