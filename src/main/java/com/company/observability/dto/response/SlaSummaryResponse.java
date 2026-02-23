package com.company.observability.dto.response;

import lombok.*;
import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaSummaryResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private String calculatorId;
    private int periodDays;
    private int totalBreaches;
    private int greenDays;
    private int amberDays;
    private int redDays;
    private Map<String, Integer> breachesBySeverity;
    private Map<String, Integer> breachesByType;
}
