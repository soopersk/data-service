// File: src/main/java/com/company/observability/service/AzureMonitorAlertSender.java
package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.exception.AlertSendException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AzureMonitorAlertSender {
    
    private final Tracer tracer;
    
    public void sendAlert(SlaBreachEvent breach, CalculatorRun run) {
        Span span = tracer.spanBuilder("sla.breach.alert")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("calculator.id", breach.getCalculatorId());
            span.setAttribute("calculator.name", breach.getCalculatorName());
            span.setAttribute("tenant.id", breach.getTenantId());
            span.setAttribute("run.id", breach.getRunId());
            span.setAttribute("breach.type", breach.getBreachType());
            span.setAttribute("severity", breach.getSeverity());
            
            span.addEvent("SLA Breach Detected",
                Attributes.of(
                    AttributeKey.stringKey("reason"), run.getSlaBreachReason(),
                    AttributeKey.longKey("expected_duration_ms"), breach.getExpectedValue() != null ? breach.getExpectedValue() : 0L,
                    AttributeKey.longKey("actual_duration_ms"), breach.getActualValue() != null ? breach.getActualValue() : 0L
                ));
            
            log.info("SLA breach alert sent to Azure Monitor for run {}", breach.getRunId());
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Failed to send alert");
            throw new AlertSendException("Failed to send alert to Azure Monitor", e);
        } finally {
            span.end();
        }
    }
}