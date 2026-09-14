package io.ledgerflow.payment.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.issuer.PaymentAuthorized;
import io.ledgerflow.events.issuer.PaymentDeclined;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.events.ledger.HoldRejected;
import io.ledgerflow.events.settlement.CapturesIssued;
import io.ledgerflow.events.settlement.IssueFailed;
import io.ledgerflow.payment.application.Payments;
import io.ledgerflow.starter.messaging.Inbox;
import io.ledgerflow.starter.messaging.InvalidPayloadException;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** Every answer the saga waits for arrives here. One method, because the saga does not care which service spoke. */
@Component
@RequiredArgsConstructor
class ReplyListener {

    private final Inbox inbox;
    private final Payments payments;
    private final JsonMapper json;

    @KafkaListener(topics = {FundsHeld.TOPIC, HoldRejected.TOPIC, PaymentAuthorized.TOPIC, CapturesIssued.TOPIC})
    void onReply(EventEnvelope<JsonNode> event, Acknowledgment ack) {
        inbox.once(event, () -> {
            switch (event.eventType()) {
                case FundsHeld.TYPE -> {
                    var held = as(event, FundsHeld.class);
                    if (held.reference() != null) payments.apply(held.reference(), held, event);   // an HTTP hold has no payment behind it
                }
                case HoldRejected.TYPE -> apply(event, as(event, HoldRejected.class).reference(), HoldRejected.class);
                case PaymentAuthorized.TYPE -> apply(event, as(event, PaymentAuthorized.class).paymentId(), PaymentAuthorized.class);
                case PaymentDeclined.TYPE -> apply(event, as(event, PaymentDeclined.class).paymentId(), PaymentDeclined.class);
                case CapturesIssued.TYPE -> apply(event, as(event, CapturesIssued.class).paymentId(), CapturesIssued.class);
                case IssueFailed.TYPE -> apply(event, as(event, IssueFailed.class).paymentId(), IssueFailed.class);
                default -> throw new InvalidPayloadException("payment does not take " + event.eventType());
            }
        });
        ack.acknowledge();
    }

    private <T extends Record> void apply(EventEnvelope<JsonNode> event, UUID paymentId, Class<T> type) {
        payments.apply(paymentId, as(event, type), event);
    }

    private <T> T as(EventEnvelope<JsonNode> event, Class<T> type) {
        return json.treeToValue(event.payload(), type);
    }
}
