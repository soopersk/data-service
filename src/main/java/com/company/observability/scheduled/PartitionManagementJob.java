package com.company.observability.scheduled;

import com.company.observability.util.MdcContextUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static com.company.observability.util.ObservabilityConstants.*;

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

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final Executor taskExecutor;

    private final AtomicLong totalRowsGauge = new AtomicLong(0L);
    private final AtomicLong dailyRowsGauge = new AtomicLong(0L);
    private final AtomicLong monthlyRowsGauge = new AtomicLong(0L);
    private final AtomicLong partitionCountGauge = new AtomicLong(0L);

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge(PARTITION_ROWS_TOTAL, totalRowsGauge);
        meterRegistry.gauge(PARTITION_ROWS_DAILY, dailyRowsGauge);
        meterRegistry.gauge(PARTITION_ROWS_MONTHLY, monthlyRowsGauge);
        meterRegistry.gauge(PARTITION_COUNT, partitionCountGauge);
    }

    /**
     * Create partitions daily at 1 AM
     * Creates partitions for next 60 days
     */
    @Scheduled(cron = "${observability.partitions.management.create-cron:0 0 1 * * *}")
    public void createPartitions() {
        Map<String, String> snapshot = MdcContextUtil.setJobContext("partition-create");

        try {
            log.info("event=partition.create outcome=started");

            taskExecutor.execute(() -> {
                Map<String, String> asyncSnapshot = MdcContextUtil.setJobContext("partition-create-async");
                try {
                    jdbcTemplate.getJdbcOperations().execute("SELECT create_calculator_run_partitions()");

                    log.info("event=partition.create outcome=success days=60");
                    meterRegistry.counter(PARTITION_CREATE_SUCCESS).increment();

                    logPartitionStatistics();

                } catch (Exception e) {
                    log.error("event=partition.create outcome=failure", e);
                    meterRegistry.counter(PARTITION_CREATE_FAILURE).increment();
                } finally {
                    MdcContextUtil.restoreContext(asyncSnapshot);
                }
            });
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    /**
     * Drop old partitions weekly on Sunday at 2 AM
     * Drops partitions older than 395 days (13+ months)
     */
    @Scheduled(cron = "${observability.partitions.management.drop-cron:0 0 2 * * SUN}")
    public void dropOldPartitions() {
        Map<String, String> snapshot = MdcContextUtil.setJobContext("partition-drop");

        try {
            log.info("event=partition.drop outcome=started");

            taskExecutor.execute(() -> {
                Map<String, String> asyncSnapshot = MdcContextUtil.setJobContext("partition-drop-async");
                try {
                    jdbcTemplate.getJdbcOperations().execute("SELECT drop_old_calculator_run_partitions()");

                    log.info("event=partition.drop outcome=success");
                    meterRegistry.counter(PARTITION_DROP_SUCCESS).increment();

                    logPartitionStatistics();

                } catch (Exception e) {
                    log.error("event=partition.drop outcome=failure", e);
                    meterRegistry.counter(PARTITION_DROP_FAILURE).increment();
                } finally {
                    MdcContextUtil.restoreContext(asyncSnapshot);
                }
            });
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    /**
     * Monitor partition health daily at 6 AM
     */
    @Scheduled(cron = "${observability.partitions.monitoring.cron:0 0 6 * * *}")
    public void monitorPartitionHealth() {
        Map<String, String> snapshot = MdcContextUtil.setJobContext("partition-monitor");

        try {
            log.info("event=partition.monitor outcome=started");

            List<Map<String, Object>> stats = jdbcTemplate.queryForList(
                    "SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 30",
                    EmptySqlParameterSource.INSTANCE
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

                log.debug("event=partition.monitor.detail partition={} rows={} daily={} monthly={} size={}",
                        stat.get("partition_name"), rowCount, daily, monthly, stat.get("total_size"));
            }

            totalRowsGauge.set(totalRows);
            dailyRowsGauge.set(dailyRows);
            monthlyRowsGauge.set(monthlyRows);
            partitionCountGauge.set(stats.size());

            log.info("event=partition.monitor outcome=success partitions={} totalRows={} dailyRows={} monthlyRows={}",
                    stats.size(), totalRows, dailyRows, monthlyRows);

        } catch (Exception e) {
            log.error("event=partition.monitor outcome=failure", e);
            meterRegistry.counter(PARTITION_MONITOR_FAILURE).increment();
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    private void logPartitionStatistics() {
        try {
            List<Map<String, Object>> recentStats = jdbcTemplate.queryForList(
                    "SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 7",
                    EmptySqlParameterSource.INSTANCE
            );

            for (Map<String, Object> stat : recentStats) {
                log.info("event=partition.stats partition={} rows={} size={}",
                        stat.get("partition_name"), stat.get("row_count"), stat.get("total_size"));
            }
        } catch (Exception e) {
            log.warn("event=partition.stats outcome=failure", e);
        }
    }
}
