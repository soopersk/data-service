package com.company.observability.config;

import com.company.observability.repository.CalculatorRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-specific metrics
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class MetricsConfiguration {

    private final CalculatorRunRepository runRepository;

    @Bean
    public MeterBinder customMetrics(MeterRegistry registry) {
        return (reg) -> {
            // Active calculator runs gauge
            Gauge.builder("calculator.runs.active", runRepository, repo -> {
                        try {
                            return repo.countRunning();
                        } catch (Exception e) {
                            log.warn("Failed to count running calculators", e);
                            return 0;
                        }
                    })
                    .description("Number of currently running calculator runs")
                    .register(reg);

            log.info("Custom metrics registered");
        };
    }
}