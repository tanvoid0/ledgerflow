package io.ledgerflow.ledger.adapter.out.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.ledger.domain.model.FundsHold;
import io.ledgerflow.starter.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HoldEventPublisher {

    private final KafkaTemplate<String, Object> kafka;

    HoldEventPublisher(KafkaTemplate<String, Object> kafka) { this.kafka = kafka; }

    /** One event per hold, keyed by the hold id: everything about one hold lands on one partition, in order. */
    public void publish(FundsHold hold) {
        var envelope = envelope(hold);
        kafka.send(FundsHeld.TOPIC, envelope.aggregateId().toString(), envelope);
    }

    EventEnvelope<FundsHeld> envelope(FundsHold hold) {
        // the HTTP request id is both: it started the story, and it directly caused this event
        var requestId = MDC.get(RequestIdFilter.MDC_KEY);
        var payload = new FundsHeld(hold.id(),
                List.of(new WalletRef(hold.accountId(), hold.walletCode())),
                hold.expiresAt(), hold.amount());
        // aggregateVersion 1: placing a hold is the first thing that ever happens to it
        return EventEnvelope.of(FundsHeld.TYPE, hold.id(), 1, requestId, requestId, payload);
    }
}
