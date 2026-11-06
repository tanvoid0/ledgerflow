package io.ledgerflow.payment.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.payment.RefundOrder;
import io.ledgerflow.payment.application.Payments;
import io.ledgerflow.starter.messaging.Inbox;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ticketing cancelled an event: give the money back for that payment. */
@Component
@RequiredArgsConstructor
class RefundOrderListener {

    private final Inbox inbox;
    private final Payments payments;
    private final JsonMapper json;

    @KafkaListener(topics = RefundOrder.TOPIC)
    void onRefund(EventEnvelope<JsonNode> event, Acknowledgment ack) {
        inbox.once(event, () -> payments.refund(json.treeToValue(event.payload(), RefundOrder.class).paymentId(), event));
        ack.acknowledge();
    }
}
