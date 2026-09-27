package io.ledgerflow.ticketing.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.payment.PaymentCaptured;
import io.ledgerflow.events.payment.PaymentFailed;
import io.ledgerflow.starter.messaging.Inbox;
import io.ledgerflow.starter.messaging.InvalidPayloadException;
import io.ledgerflow.ticketing.application.Ticketing;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The saga's terminal answer for a checkout: CONFIRMED + SOLD, or FAILED + seats back to FREE. */
@Component
@RequiredArgsConstructor
class PaymentOutcomeListener {

    private final Inbox inbox;
    private final Ticketing ticketing;
    private final JsonMapper json;

    @KafkaListener(topics = PaymentCaptured.TOPIC)
    void onOutcome(EventEnvelope<JsonNode> event, Acknowledgment ack) {
        inbox.once(event, () -> {
            switch (event.eventType()) {
                case PaymentCaptured.TYPE -> {
                    var c = as(event, PaymentCaptured.class);
                    ticketing.confirm(c.paymentId(), event.correlationId(), c.accountId(), c.amountMinor(), c.currency());
                }
                case PaymentFailed.TYPE -> {
                    var f = as(event, PaymentFailed.class);
                    ticketing.fail(f.paymentId(), event.correlationId(), f.accountId(), f.amountMinor(), f.currency(), f.reason());
                }
                default -> throw new InvalidPayloadException("ticketing does not take " + event.eventType());
            }
        });
        ack.acknowledge();
    }

    private <T> T as(EventEnvelope<JsonNode> event, Class<T> type) {
        return json.treeToValue(event.payload(), type);
    }
}
