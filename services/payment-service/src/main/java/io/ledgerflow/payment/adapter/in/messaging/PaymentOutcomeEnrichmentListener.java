package io.ledgerflow.payment.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.payment.PaymentCaptured;
import io.ledgerflow.events.payment.PaymentRefunded;
import io.ledgerflow.payment.application.Enrichment;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 3 enrichment: reads the outcome topic the saga already publishes (captured / refunded / failed share it)
 * and projects a category for the two terminal events worth tagging. Off the money path — it tags a read model,
 * never touches the saga. PaymentFailed is ignored (nothing settled to categorise).
 */
@Component
@RequiredArgsConstructor
class PaymentOutcomeEnrichmentListener {

    private final Enrichment enrichment;
    private final JsonMapper json;

    @KafkaListener(topics = PaymentCaptured.TOPIC)
    void onOutcome(EventEnvelope<JsonNode> event, Acknowledgment ack) {
        switch (event.eventType()) {
            case PaymentCaptured.TYPE -> {
                var c = json.treeToValue(event.payload(), PaymentCaptured.class);
                enrichment.project(event, c.paymentId(), Enrichment.PAYMENT, c.amountMinor(), c.currency());
            }
            case PaymentRefunded.TYPE -> {
                var r = json.treeToValue(event.payload(), PaymentRefunded.class);
                enrichment.project(event, r.paymentId(), Enrichment.REFUND, r.amountMinor(), r.currency());
            }
            default -> { /* PaymentFailed and anything else on this topic: nothing settled, nothing to tag */ }
        }
        ack.acknowledge();
    }
}
