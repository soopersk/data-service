package com.company.observability.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterNameDiscoverer;

@Configuration
public class ParameterNameDiscovererConfig {

    /**
     * Fixes the NoUniqueBeanDefinitionException caused by
     * OpenTelemetry + SpringDoc both defining a ParameterNameDiscoverer.
     */
    @Primary
    @Bean
    public ParameterNameDiscoverer primaryParameterNameDiscoverer(
            ParameterNameDiscoverer parameterNameDiscoverer
    ) {
        // Pick the OpenTelemetry bean as the primary one
        return parameterNameDiscoverer;
    }
}
