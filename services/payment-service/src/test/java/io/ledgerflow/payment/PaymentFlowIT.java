package io.ledgerflow.payment;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.issuer.AuthorizePayment;
import io.ledgerflow.events.issuer.PaymentAuthorized;
import io.ledgerflow.events.ledger.CaptureHolds;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.events.ledger.ReleaseWallets;
import io.ledgerflow.events.ledger.ReserveWallets;
import io.ledgerflow.events.payment.PaymentRequested;
import io.ledgerflow.events.settlement.CapturesIssued;
import io.ledgerflow.events.settlement.IssueCaptures;
import io.ledgerflow.payment.application.Payments;
import io.ledgerflow.payment.domain.model.PaymentState;
import io.ledgerflow.payment.domain.model.PaymentState.Captured;
import io.ledgerflow.payment.domain.model.PaymentState.Failed;
import io.ledgerflow.payment.domain.model.PaymentState.FailureReason;
import io.ledgerflow.payment.domain.model.PaymentState.Step;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The saga against a real broker and database, with the other three services played by the test:
 * it reads the commands payment queued and answers them on the reply topics.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
// Boot propagates trace context only while export is on, and @SpringBootTest turns export off: on again, but not to anywhere
@AutoConfigureTracing
@TestPropertySource(properties = {"ledgerflow.step-deadline=3s", "management.tracing.export.otlp.enabled=false"})
class PaymentFlowIT {

    static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired Payments payments;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;
    @Autowired ObservationRegistry observations;
    @Autowired Tracer tracer;
    @Autowired ConsumerFactory<String, String> consumers;

    @Test
    void everyServiceAnswers_thePaymentReachesCaptured() {
        var id = payments.start(ACCOUNT, List.of("A-12"), Money.gbp(4500), "shop-1").paymentId();
        // risk-service's event goes out in the same transaction as the first command, right after it
        await().untilAsserted(() -> assertThat(sent(id)).containsExactly(ReserveWallets.TYPE, PaymentRequested.TYPE));

        var hold = UUID.randomUUID();
        reply(FundsHeld.TOPIC, FundsHeld.TYPE, hold, new FundsHeld(hold, List.of(new WalletRef(ACCOUNT, "A-12")), Instant.now(), Money.gbp(4500), id));
        await().untilAsserted(() -> assertThat(sent(id)).containsExactly(ReserveWallets.TYPE, PaymentRequested.TYPE, AuthorizePayment.TYPE));

        reply(PaymentAuthorized.TOPIC, PaymentAuthorized.TYPE, id, new PaymentAuthorized(id, UUID.randomUUID(), Money.gbp(4500)));
        await().untilAsserted(() -> assertThat(sent(id)).containsExactly(ReserveWallets.TYPE, PaymentRequested.TYPE, AuthorizePayment.TYPE, IssueCaptures.TYPE));

        var capture = UUID.randomUUID();
        reply(CapturesIssued.TOPIC, CapturesIssued.TYPE, id, new CapturesIssued(id, List.of(capture)));
        await().untilAsserted(() -> assertThat(state(id)).isEqualTo(new Captured(id, List.of(capture))));
        assertThat(sent(id)).endsWith(CaptureHolds.TYPE);
    }

    @Test
    void nobodyAnswers_theSweeperFailsThePaymentAndALateHoldChangesNothing() {
        var id = payments.start(ACCOUNT, List.of("A-12"), Money.gbp(4500), null).paymentId();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(state(id)).isEqualTo(new Failed(id, FailureReason.TIMED_OUT, Step.RESERVE)));
        assertThat(sent(id)).containsExactly(ReserveWallets.TYPE, PaymentRequested.TYPE, ReleaseWallets.TYPE);

        var hold = UUID.randomUUID();
        var late = reply(FundsHeld.TOPIC, FundsHeld.TYPE, hold, new FundsHeld(hold, List.of(new WalletRef(ACCOUNT, "A-12")), Instant.now(), Money.gbp(4500), id));
        await().untilAsserted(() -> assertThat(handled(late)).isTrue());
        assertThat(state(id)).isEqualTo(new Failed(id, FailureReason.TIMED_OUT, Step.RESERVE));
        assertThat(sent(id)).containsExactly(ReserveWallets.TYPE, PaymentRequested.TYPE, ReleaseWallets.TYPE);   // the release already sent covers the late hold
        assertThat(meters.get("ledgerflow.saga.compensated").tags("step", "RESERVE", "reason", "TIMED_OUT").counter().count()).isEqualTo(1);
    }

    /** The outbox row was written on the request's thread; the poller sends it on its own. Same trace, or the story breaks here. */
    @Test
    void theCommandOnTheWireCarriesTheTraceOfTheRequestThatStartedThePayment() {
        var trace = new String[1];
        var id = Observation.createNotStarted("request", observations).observe(() -> {
            trace[0] = tracer.currentSpan().context().traceId();
            return payments.start(ACCOUNT, List.of("A-12"), Money.gbp(4500), null).paymentId();
        });

        assertThat(trace[0]).hasSize(32);   // a real trace id, not the no-op tracer's empty one
        var command = onTheWire(ReserveWallets.TOPIC, id);
        var traceparent = new String(command.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
        assertThat(traceparent).contains(trace[0]);
    }

    private ConsumerRecord<String, String> onTheWire(String topic, UUID key) {
        try (var consumer = consumers.createConsumer("wire-" + UUID.randomUUID(), "")) {
            consumer.subscribe(List.of(topic));
            var deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.key().equals(key.toString())) return record;
                }
            }
            throw new AssertionError("nothing for " + key + " on " + topic);
        }
    }

    private UUID reply(String topic, String type, UUID key, Object payload) {
        var envelope = EventEnvelope.of(type, key, 1, "req-1", "req-1", payload);
        kafka.send(topic, key.toString(), json.writeValueAsString(envelope));
        return envelope.eventId();
    }

    private PaymentState state(UUID id) {
        return payments.find(id).orElseThrow();
    }

    /** What payment asked the other services to do, in order. */
    private List<String> sent(UUID id) {
        return db.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY occurred_at").param("id", id)
                .query(String.class).list();
    }

    private boolean handled(UUID eventId) {
        return db.sql("SELECT count(*) FROM processed_events WHERE event_id = :id").param("id", eventId).query(Integer.class).single() == 1;
    }
}
