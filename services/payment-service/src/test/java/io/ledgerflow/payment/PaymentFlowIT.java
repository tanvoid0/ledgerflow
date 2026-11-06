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
import io.ledgerflow.events.payment.PaymentCaptured;
import io.ledgerflow.events.payment.PaymentFailed;
import io.ledgerflow.events.payment.PaymentRefunded;
import io.ledgerflow.events.payment.PaymentRequested;
import io.ledgerflow.events.payment.RefundOrder;
import io.ledgerflow.events.settlement.RevokeCaptures;
import io.ledgerflow.payment.domain.model.PaymentState.Refunded;
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
        await().untilAsserted(() -> assertThat(state(id)).isEqualTo(new Captured(id, List.of(capture), ACCOUNT, Money.gbp(4500))));
        // CaptureHolds and the outcome event go out in the same transaction, close enough to tie on occurred_at
        var sent = sent(id);
        assertThat(sent).startsWith(ReserveWallets.TYPE, PaymentRequested.TYPE, AuthorizePayment.TYPE, IssueCaptures.TYPE);
        assertThat(sent.subList(4, 6)).containsExactlyInAnyOrder(CaptureHolds.TYPE, PaymentCaptured.TYPE);
        assertThat(outcome(id, PaymentCaptured.class, PaymentCaptured.TYPE)).isEqualTo(new PaymentCaptured(id, ACCOUNT, 4500, "GBP"));
    }

    @Test
    void refundOrder_revokesAndAnnouncesOnce_andOnlyForACapturedPayment() {
        var waiting = payments.start(ACCOUNT, List.of("A-12"), Money.gbp(4500), null).paymentId();
        var before = sent(waiting).size();
        var first = reply(RefundOrder.TOPIC, RefundOrder.TYPE, waiting, new RefundOrder(waiting));
        await().untilAsserted(() -> assertThat(handled(first)).isTrue());
        assertThat(state(waiting)).isInstanceOf(PaymentState.Requested.class);   // not captured: nothing to give back
        assertThat(sent(waiting)).hasSize(before);

        var id = captured();
        reply(RefundOrder.TOPIC, RefundOrder.TYPE, id, new RefundOrder(id));
        await().untilAsserted(() -> assertThat(state(id)).isInstanceOf(Refunded.class));
        await().untilAsserted(() -> assertThat(sent(id).subList(6, 8)).containsExactlyInAnyOrder(RevokeCaptures.TYPE, PaymentRefunded.TYPE));
        assertThat(outcome(id, PaymentRefunded.class, PaymentRefunded.TYPE)).isEqualTo(new PaymentRefunded(id, ACCOUNT, 4500, "GBP"));

        var again = reply(RefundOrder.TOPIC, RefundOrder.TYPE, id, new RefundOrder(id));
        await().untilAsserted(() -> assertThat(handled(again)).isTrue());
        assertThat(sent(id)).hasSize(8);   // the second cancel changes nothing
    }

    /** A payment driven all the way to Captured; its outbox holds the six commands and events up to there. */
    private UUID captured() {
        var id = payments.start(ACCOUNT, List.of("A-12"), Money.gbp(4500), null).paymentId();
        var hold = UUID.randomUUID();
        reply(FundsHeld.TOPIC, FundsHeld.TYPE, hold, new FundsHeld(hold, List.of(new WalletRef(ACCOUNT, "A-12")), Instant.now(), Money.gbp(4500), id));
        await().untilAsserted(() -> assertThat(sent(id)).contains(AuthorizePayment.TYPE));
        reply(PaymentAuthorized.TOPIC, PaymentAuthorized.TYPE, id, new PaymentAuthorized(id, UUID.randomUUID(), Money.gbp(4500)));
        await().untilAsserted(() -> assertThat(sent(id)).contains(IssueCaptures.TYPE));
        reply(CapturesIssued.TOPIC, CapturesIssued.TYPE, id, new CapturesIssued(id, List.of(UUID.randomUUID())));
        await().untilAsserted(() -> assertThat(state(id)).isInstanceOf(Captured.class));
        return id;
    }

    @Test
    void nobodyAnswers_theSweeperFailsThePaymentAndALateHoldChangesNothing() {
        var id = payments.start(ACCOUNT, List.of("A-12"), Money.gbp(4500), null).paymentId();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(state(id)).isEqualTo(new Failed(id, FailureReason.TIMED_OUT, Step.RESERVE)));
        assertThat(sent(id)).startsWith(ReserveWallets.TYPE, PaymentRequested.TYPE);
        assertThat(sent(id).subList(2, 4)).containsExactlyInAnyOrder(ReleaseWallets.TYPE, PaymentFailed.TYPE);
        assertThat(outcome(id, PaymentFailed.class, PaymentFailed.TYPE)).isEqualTo(new PaymentFailed(id, "TIMED_OUT", "RESERVE", ACCOUNT, 4500, "GBP"));

        var hold = UUID.randomUUID();
        var late = reply(FundsHeld.TOPIC, FundsHeld.TYPE, hold, new FundsHeld(hold, List.of(new WalletRef(ACCOUNT, "A-12")), Instant.now(), Money.gbp(4500), id));
        await().untilAsserted(() -> assertThat(handled(late)).isTrue());
        assertThat(state(id)).isEqualTo(new Failed(id, FailureReason.TIMED_OUT, Step.RESERVE));
        assertThat(sent(id)).hasSize(4);   // the release and outcome already sent cover the late hold, nothing new goes out
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

    /** The outcome event's payload as ticketing will read it. */
    private <T> T outcome(UUID id, Class<T> type, String eventType) {
        return json.readValue(db.sql("SELECT payload->>'payload' FROM outbox WHERE aggregate_id = :id AND topic = :topic AND event_type = :type")
                .param("id", id).param("topic", PaymentCaptured.TOPIC).param("type", eventType).query(String.class).single(), type);
    }

    private boolean handled(UUID eventId) {
        return db.sql("SELECT count(*) FROM processed_events WHERE event_id = :id").param("id", eventId).query(Integer.class).single() == 1;
    }
}
