package io.ledgerflow.issuer.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.issuer.AuthorizePayment;
import io.ledgerflow.events.issuer.PaymentAuthorized;
import io.ledgerflow.events.issuer.PaymentDeclined;
import io.ledgerflow.events.issuer.RefundPayment;
import io.ledgerflow.starter.messaging.Inbox;
import io.ledgerflow.starter.messaging.InvalidPayloadException;
import io.ledgerflow.starter.messaging.OutboxAppender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * A stub issuer with one rule: an amount of exactly 1 is declined, everything else is approved.
 * That is enough to drive every unhappy path of the saga on demand.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AuthorizationCommandListener {

    static final long ALWAYS_DECLINED = 1;

    private final Inbox inbox;
    private final JdbcClient db;
    private final OutboxAppender outbox;
    private final JsonMapper json;

    @KafkaListener(topics = AuthorizePayment.TOPIC)
    void onCommand(EventEnvelope<JsonNode> command, Acknowledgment ack) {
        inbox.once(command, () -> {
            switch (command.eventType()) {
                case AuthorizePayment.TYPE -> authorize(command, json.treeToValue(command.payload(), AuthorizePayment.class));
                case RefundPayment.TYPE -> refund(json.treeToValue(command.payload(), RefundPayment.class).paymentId());
                default -> throw new InvalidPayloadException("issuer does not take " + command.eventType());
            }
        });
        ack.acknowledge();
    }

    private void authorize(EventEnvelope<?> command, AuthorizePayment c) {
        if (c.amount().minorUnits() == ALWAYS_DECLINED) {
            log.info("declining payment {}", c.paymentId());
            outbox.append(PaymentDeclined.TOPIC, EventEnvelope.inReplyTo(command, PaymentDeclined.TYPE, c.paymentId(), 1,
                    new PaymentDeclined(c.paymentId(), "amount of 1 is always declined")));
            return;
        }
        // a second AuthorizePayment for the same payment answers with the authorization it already has
        var id = db.sql("""
                INSERT INTO authorizations (id, payment_id, amount_minor, currency, status)
                VALUES (:id, :paymentId, :amount, :currency, 'AUTHORIZED')
                ON CONFLICT (payment_id) DO UPDATE SET payment_id = EXCLUDED.payment_id
                RETURNING id
                """)
                .param("id", UUID.randomUUID()).param("paymentId", c.paymentId())
                .param("amount", c.amount().minorUnits()).param("currency", c.amount().currency())
                .query(UUID.class).single();
        log.info("authorized payment {} as {}", c.paymentId(), id);
        outbox.append(PaymentAuthorized.TOPIC, EventEnvelope.inReplyTo(command, PaymentAuthorized.TYPE, c.paymentId(), 1,
                new PaymentAuthorized(c.paymentId(), id, c.amount())));
    }

    /** Nothing to refund is not an error: the saga sends this whenever it is unsure whether we ever authorized. */
    private void refund(UUID paymentId) {
        var refunded = db.sql("UPDATE authorizations SET status = 'REFUNDED' WHERE payment_id = :paymentId AND status = 'AUTHORIZED'")
                .param("paymentId", paymentId).update();
        log.info("refund for payment {}: {} authorization(s) reversed", paymentId, refunded);
    }
}
