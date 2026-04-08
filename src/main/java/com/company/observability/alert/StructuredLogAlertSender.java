package com.company.observability.alert;

import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.util.MdcContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class StructuredLogAlertSender implements AlertSender {

    @Override
    public void send(SlaBreachEvent breach) throws AlertDeliveryException {
        if (breach == null) {
            throw new AlertDeliveryException("breach payload is required");
        }
        if (breach.getSeverity() == null) {
            throw new AlertDeliveryException("severity is required for alert routing");
        }

        Map<String, String> snapshot = MdcContextUtil.setAlertContext(breach);
        try {
            log.error("SLA_BREACH_ALERT | calculator={} severity={} breach={} tenant={}",
                    breach.getCalculatorId(),
                    breach.getSeverity().name(),
                    breach.getBreachType() != null ? breach.getBreachType().name() : "UNKNOWN",
                    breach.getTenantId());
        } catch (Exception e) {
            throw new AlertDeliveryException("Failed to emit structured alert log", e);
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }

    @Override
    public String channelName() {
        return "logging";
    }
}
