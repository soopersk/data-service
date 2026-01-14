package com.company.observability.cache;

import com.company.observability.repository.CalculatorRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Listen to Redis key expiration events
 * When SLA timer expires, mark the run as breached in database
 */
@Component
@Slf4j
public class SlaExpiryListener extends KeyExpirationEventMessageListener {

    private final CalculatorRunRepository runRepository;

    public SlaExpiryListener(RedisMessageListenerContainer container,
                             CalculatorRunRepository runRepository) {
        super(container);
        this.runRepository = runRepository;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();

        if (expiredKey.startsWith("sla_timer:")) {
            String runId = expiredKey.substring(10);

            log.warn("SLA timer expired for run {} - marking as breached", runId);

            try {
                runRepository.markSlaBreached(runId);
                log.info("Marked run {} as SLA breached due to timeout", runId);
            } catch (Exception e) {
                log.error("Failed to mark run {} as breached", runId, e);
            }
        }
    }
}