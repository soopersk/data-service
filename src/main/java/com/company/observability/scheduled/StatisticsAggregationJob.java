package com.company.observability.scheduled;

import com.company.observability.domain.Calculator;
import com.company.observability.domain.CalculatorStatistics;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.repository.CalculatorRepository;
import com.company.observability.repository.CalculatorStatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.statistics.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class StatisticsAggregationJob {

    private final JdbcTemplate jdbcTemplate;
    private final CalculatorRepository calculatorRepository;
    private final CalculatorStatisticsRepository statisticsRepository;

    /**
     * Aggregate statistics daily at 2 AM
     * Computes average runtime, SLA compliance, etc. for all active calculators
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void aggregateDailyStatistics() {
        log.info("Starting daily statistics aggregation");

        List<Calculator> activeCalculators = calculatorRepository.findAllActive();

        int successCount = 0;
        int failureCount = 0;

        for (Calculator calculator : activeCalculators) {
            try {
                aggregateStatisticsForCalculator(calculator.getCalculatorId(), "default");
                successCount++;
            } catch (Exception e) {
                log.error("Failed to aggregate statistics for calculator {}",
                        calculator.getCalculatorId(), e);
                failureCount++;
            }
        }

        log.info("Daily statistics aggregation completed: {} succeeded, {} failed",
                successCount, failureCount);
    }

    @Transactional
    protected void aggregateStatisticsForCalculator(String calculatorId, String tenantId) {
        Calculator calculator = calculatorRepository.findById(calculatorId).orElse(null);
        if (calculator == null) {
            log.warn("Calculator not found: {}", calculatorId);
            return;
        }

        String frequency = calculator.getFrequency();
        int lookbackDays = CalculatorFrequency.valueOf(frequency).getLookbackDays();

        log.debug("Calculating statistics for {} calculator {} (lookback: {} days)",
                frequency, calculatorId, lookbackDays);

        Map<String, Object> stats = calculateStatistics(calculatorId, tenantId, lookbackDays);

        Instant now = Instant.now();
        Instant periodStart = now.minus(Duration.ofDays(lookbackDays));

        CalculatorStatistics statRecord = CalculatorStatistics.builder()
                .calculatorId(calculatorId)
                .tenantId(tenantId)
                .periodDays(lookbackDays)
                .periodStart(periodStart)
                .periodEnd(now)
                .totalRuns(getIntValue(stats, "total_runs"))
                .successfulRuns(getIntValue(stats, "successful_runs"))
                .failedRuns(getIntValue(stats, "failed_runs"))
                .avgDurationMs(getLongValue(stats, "avg_duration_ms"))
                .minDurationMs(getLongValue(stats, "min_duration_ms"))
                .maxDurationMs(getLongValue(stats, "max_duration_ms"))
                .avgStartHourCet(getBigDecimalValue(stats, "avg_start_hour_cet"))
                .avgEndHourCet(getBigDecimalValue(stats, "avg_end_hour_cet"))
                .slaBreaches(getIntValue(stats, "sla_breaches"))
                .build();

        statisticsRepository.save(statRecord);

        log.info("Aggregated statistics for calculator {} over {} days: {} total runs, {} breaches",
                calculatorId, lookbackDays, statRecord.getTotalRuns(), statRecord.getSlaBreaches());
    }

    private Map<String, Object> calculateStatistics(String calculatorId, String tenantId, int lookbackDays) {
        String sql = """
                SELECT 
                    COUNT(*) as total_runs,
                    COUNT(*) FILTER (WHERE status = 'SUCCESS') as successful_runs,
                    COUNT(*) FILTER (WHERE status IN ('FAILED', 'TIMEOUT')) as failed_runs,
                    AVG(duration_ms) as avg_duration_ms,
                    MIN(duration_ms) as min_duration_ms,
                    MAX(duration_ms) as max_duration_ms,
                    AVG(start_hour_cet) as avg_start_hour_cet,
                    AVG(end_hour_cet) as avg_end_hour_cet,
                    COUNT(*) FILTER (WHERE sla_breached = true) as sla_breaches
                FROM calculator_runs
                WHERE calculator_id = ?
                AND tenant_id = ?
                AND status IN ('SUCCESS', 'FAILED', 'TIMEOUT')
                AND created_at >= NOW() - INTERVAL '? days'
                """;

        return jdbcTemplate.queryForMap(sql, calculatorId, tenantId, lookbackDays);
    }

    // Helper methods to safely extract values from Map
    private Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private BigDecimal getBigDecimalValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return null;
    }
}