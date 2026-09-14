package io.ledgerflow.ledger.adapter.out.messaging;

import io.ledgerflow.ledger.domain.event.FundsHeld;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class HoldEventPublisher {

    static final String TOPIC = "wallet-hold-events";

    private final KafkaTemplate<String, Object> kafka;

    HoldEventPublisher(KafkaTemplate<String, Object> kafka) { this.kafka = kafka; }

    /** Keyed by hold id: every event about one hold lands on the same partition, in order. */
    public void publish(FundsHeld event) {
        kafka.send(TOPIC, event.holdId().toString(), event);
    }
}
