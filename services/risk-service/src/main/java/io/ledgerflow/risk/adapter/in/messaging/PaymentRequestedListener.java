package io.ledgerflow.risk.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.payment.PaymentRequested;
import io.ledgerflow.risk.application.Decisions;
import io.ledgerflow.risk.application.FeatureWindow;
import io.ledgerflow.risk.application.RiskProperties;
import io.ledgerflow.risk.application.Rules;
import io.ledgerflow.risk.application.Scorer;
import io.ledgerflow.starter.messaging.Inbox;
import io.ledgerflow.starter.messaging.InvalidPayloadException;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every requested payment, scored on its own: nothing here replies to the saga, nothing here blocks it. */
@Component
@RequiredArgsConstructor
class PaymentRequestedListener {

    private final Inbox inbox;
    private final FeatureWindow features;
    private final Scorer scorer;
    private final Rules rules;
    private final RiskProperties props;
    private final Decisions decisions;
    private final JsonMapper json;

    @KafkaListener(topics = PaymentRequested.TOPIC)
    void onPaymentRequested(EventEnvelope<JsonNode> event, Acknowledgment ack) {
        if (event.payload() == null) throw new InvalidPayloadException("event " + event.eventId() + " has no payload");
        inbox.once(event, () -> {
            var p = json.treeToValue(event.payload(), PaymentRequested.class);
            // empty on replay after a rolled-back transaction: the counters were already touched, this delivery skips
            features.observe(event.eventId().toString(), p.accountId(), p.amount().minorUnits(), p.beneficiary(), event.occurredAt().toEpochMilli())
                    .ifPresent(f -> {
                        var score = scorer.score(f);
                        var decision = rules.decide(p, f, score, scorer.modelVersion(), props.reviewThreshold());
                        decisions.record(p.paymentId(), decision, storedFeatures(p, f), score, scorer.modelVersion());
                    });
        });
        ack.acknowledge();
    }

    private static Decisions.StoredFeatures storedFeatures(PaymentRequested p, FeatureWindow.Features f) {
        return new Decisions.StoredFeatures(p.amount().minorUnits(), p.amount().currency(), p.beneficiary(),
                f.countLastMinute(), f.amountZScore(), f.newBeneficiary());
    }
}
