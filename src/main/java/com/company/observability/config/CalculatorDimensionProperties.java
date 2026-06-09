package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "observability")
@Getter
@Setter
public class CalculatorDimensionProperties {

    private Map<String, DimensionConfig> calculatorDimensions = new HashMap<>();

    @Getter
    @Setter
    public static class DimensionConfig {
        private DimensionType type;
        private List<String> values;
    }

    public enum DimensionType {
        REGION, RUN_TYPE
    }
}
