package com.company.observability.scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class PartitionManagementJob {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Create partitions daily at 1 AM
     * Creates partitions for the next 30 days
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void createPartitions() {
        try {
            log.info("Starting partition creation job");

            jdbcTemplate.execute("SELECT create_calculator_run_partitions()");

            log.info("Successfully created calculator_runs partitions");

        } catch (Exception e) {
            log.error("Failed to create partitions", e);
        }
    }

    /**
     * Drop old partitions weekly at 2 AM on Sunday
     * Removes partitions older than 90 days
     */
    @Scheduled(cron = "0 0 2 * * SUN")
    @Transactional
    public void dropOldPartitions() {
        try {
            log.info("Starting old partition cleanup job");

            jdbcTemplate.execute("SELECT drop_old_calculator_run_partitions()");

            log.info("Successfully dropped old calculator_runs partitions");

        } catch (Exception e) {
            log.error("Failed to drop old partitions", e);
        }
    }
}