package io.ledgerflow.notification.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.notification.application.Notifier;
import io.ledgerflow.starter.messaging.Inbox;
import io.ledgerflow.starter.messaging.InvalidPayloadException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// retries and the dead letter topic come from the starter: 1s, 3s, 9s, then <topic>.notification-service.dlt
@Component
class FundsHoldListener {

    private final Inbox inbox;
    private final Notifier notifier;

    FundsHoldListener(Inbox inbox, Notifier notifier) {
        this.inbox = inbox;
        this.notifier = notifier;
    }

    @KafkaListener(topics = FundsHeld.TOPIC)
    void onFundsHeld(EventEnvelope<FundsHeld> event, Acknowledgment ack) {
        var held = event.payload();
        if (held == null || held.wallets() == null || held.wallets().isEmpty()) {
            throw new InvalidPayloadException("event " + event.eventId() + " names no wallet");
        }
        inbox.once(event, () -> notifier.send(held));
        ack.acknowledge();   // after the commit, never before
    }
}
