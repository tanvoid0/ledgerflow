package io.ledgerflow.payment;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.payment.PaymentCaptured;
import io.ledgerflow.events.payment.PaymentFailed;
import io.ledgerflow.events.payment.PaymentRefunded;
import io.ledgerflow.payment.application.Enrichment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 3: the async enrichment listener against a real broker + database. The LLM is left unconfigured
 * (blank url), so every event falls back to "uncategorised" — which is exactly the "platform down, the
 * stream still projects" guarantee, proven without a live model. Replaying an event must not double-write.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"management.tracing.export.otlp.enabled=false", "llm.url="})
class PaymentEnrichmentIT {

    static final UUID ACCOUNT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;

    @Test
    void capturedAndRefunded_projectUncategorised_andReplayDoesNotDoubleWrite() {
        var captured = UUID.randomUUID();
        var envelope = EventEnvelope.of(PaymentCaptured.TYPE, captured, 1, "req-1", "req-1",
                new PaymentCaptured(captured, ACCOUNT, 4500, "GBP"));
        send(envelope);
        await().untilAsserted(() -> assertThat(row(captured, Enrichment.PAYMENT)).isEqualTo("uncategorised"));

        var refunded = UUID.randomUUID();
        send(EventEnvelope.of(PaymentRefunded.TYPE, refunded, 1, "req-2", "req-2",
                new PaymentRefunded(refunded, ACCOUNT, 1200, "GBP")));
        await().untilAsserted(() -> assertThat(row(refunded, Enrichment.REFUND)).isEqualTo("uncategorised"));

        // the identical envelope (same eventId) again: the inbox claim and the (payment_id, kind) key both hold the line
        send(envelope);
        send(envelope);
        await().untilAsserted(() -> assertThat(handled(envelope.eventId())).isTrue());
        assertThat(count(captured)).isEqualTo(1);

        // the refund breakdown the assistant will group on
        var refunds = db.sql("SELECT count(*) FROM payment_enrichment WHERE kind = 'refund' AND category = 'uncategorised'")
                .query(Integer.class).single();
        assertThat(refunds).isEqualTo(1);
    }

    @Test
    void paymentFailed_isNotProjected() {
        var failed = UUID.randomUUID();
        send(EventEnvelope.of(PaymentFailed.TYPE, failed, 1, "req-3", "req-3",
                new PaymentFailed(failed, "TIMED_OUT", "RESERVE", ACCOUNT, 4500, "GBP")));
        // a captured after it: when its row lands, the failed ahead of it on the topic has already been seen and skipped
        var captured = UUID.randomUUID();
        send(EventEnvelope.of(PaymentCaptured.TYPE, captured, 1, "req-4", "req-4",
                new PaymentCaptured(captured, ACCOUNT, 500, "GBP")));
        await().untilAsserted(() -> assertThat(count(captured)).isEqualTo(1));
        assertThat(count(failed)).isZero();
    }

    private void send(EventEnvelope<?> envelope) {
        kafka.send(PaymentCaptured.TOPIC, envelope.aggregateId().toString(), json.writeValueAsString(envelope));
    }

    private String row(UUID paymentId, String kind) {
        return db.sql("SELECT category FROM payment_enrichment WHERE payment_id = :id AND kind = :kind")
                .param("id", paymentId).param("kind", kind).query(String.class).optional().orElse(null);
    }

    private int count(UUID paymentId) {
        return db.sql("SELECT count(*) FROM payment_enrichment WHERE payment_id = :id").param("id", paymentId)
                .query(Integer.class).single();
    }

    private boolean handled(UUID eventId) {
        return db.sql("SELECT count(*) FROM processed_events WHERE event_id = :id").param("id", eventId)
                .query(Integer.class).single() == 1;
    }
}
