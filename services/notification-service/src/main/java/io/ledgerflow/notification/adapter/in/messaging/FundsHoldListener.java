package io.ledgerflow.notification.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.ledger.FundsHeld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class FundsHoldListener {

    private static final Logger log = LoggerFactory.getLogger(FundsHoldListener.class);

    @KafkaListener(topics = FundsHeld.TOPIC)
    void onFundsHeld(EventEnvelope<FundsHeld> event) {
        var held = event.payload();
        log.info("would email the customer: {} {} held on {} until {} (event {}, request {})",
                held.totalAmount().currency(), held.totalAmount().minorUnits(),
                held.wallets().getFirst().label(), held.expiresAt(),
                event.eventId(), event.correlationId());
    }
}
