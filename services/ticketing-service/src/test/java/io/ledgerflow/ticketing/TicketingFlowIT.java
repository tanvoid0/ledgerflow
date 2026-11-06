package io.ledgerflow.ticketing;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.payment.PaymentCaptured;
import io.ledgerflow.events.payment.PaymentFailed;
import io.ledgerflow.ticketing.adapter.out.payment.PaymentClient;
import io.ledgerflow.ticketing.application.Ticketing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The whole order lifecycle against a real broker and database, with payment-service played by a mock:
 * ticketing's own SQL is what is under test here, not the saga (PaymentFlowIT already covers that).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "ticketing.hold-ttl=1s")   // short enough that the expiry test needs no sleep loop of its own
class TicketingFlowIT {

    @Autowired Ticketing ticketing;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;

    @MockitoBean PaymentClient paymentClient;

    @Test
    void twentyThreadsRaceOneSeatAndExactlyOneWins() throws Exception {
        var event = ticketing.createEvent("Race Night", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-race", 1000, "GBP", 1, 1);
        var seat = ticketing.event(event.id()).orElseThrow().seats().getFirst().seat();

        var pool = Executors.newFixedThreadPool(20);
        var ready = new CountDownLatch(20);
        var go = new CountDownLatch(1);
        var succeeded = new AtomicInteger();
        var tasks = IntStream.range(0, 20).mapToObj(i -> pool.submit(() -> {
            ready.countDown();
            go.await();
            try {
                ticketing.hold(event.id(), UUID.randomUUID(), "u" + i, List.of(seat));
                succeeded.incrementAndGet();
            } catch (ErrorResponseException e) {
                // expected for the 19 losers: the seat was no longer FREE by the time their UPDATE ran
            }
            return null;
        })).toList();
        ready.await();
        go.countDown();
        for (var t : tasks) t.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(succeeded.get()).isEqualTo(1);
    }

    @Test
    void holdCheckoutCaptureConfirmsAndSoldsTheSeat_exactRedeliveryIsUnchanged() {
        var event = ticketing.createEvent("Capture Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-capture", 2000, "GBP", 1, 1);
        var seat = ticketing.event(event.id()).orElseThrow().seats().getFirst().seat();
        var accountId = UUID.randomUUID();
        var orderId = ticketing.hold(event.id(), accountId, "alice", List.of(seat));

        var paymentId = UUID.randomUUID();
        when(paymentClient.pay(any(), any(), eq(accountId), anyLong(), any(), any())).thenReturn(paymentId);
        var order = ticketing.checkout(orderId, accountId, "Bearer t", "req-" + orderId);
        assertThat(order.paymentId()).isEqualTo(paymentId);
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");

        // the exact same eventId, sent twice: Inbox's dedupe on event_id is what makes this a no-op the second time
        var envelope = EventEnvelope.of(PaymentCaptured.TYPE, paymentId, 1, "req-" + orderId, "req-" + orderId,
                new PaymentCaptured(paymentId, accountId, 2000, "GBP"));
        var wire = json.writeValueAsString(envelope);
        kafka.send(PaymentCaptured.TOPIC, paymentId.toString(), wire);
        await().untilAsserted(() -> assertThat(ticketing.order(orderId).orElseThrow().status()).isEqualTo("CONFIRMED"));
        assertThat(seatStatus(event.id(), seat)).isEqualTo("SOLD");

        kafka.send(PaymentCaptured.TOPIC, paymentId.toString(), wire);
        await().during(Duration.ofMillis(500)).untilAsserted(() -> {
            assertThat(ticketing.order(orderId).orElseThrow().status()).isEqualTo("CONFIRMED");
            assertThat(seatStatus(event.id(), seat)).isEqualTo("SOLD");
        });
    }

    @Test
    void checkoutThenPaymentFailedFreesTheSeat() {
        var event = ticketing.createEvent("Failing Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-fail", 2000, "GBP", 1, 1);
        var seat = ticketing.event(event.id()).orElseThrow().seats().getFirst().seat();
        var accountId = UUID.randomUUID();
        var orderId = ticketing.hold(event.id(), accountId, "alice", List.of(seat));

        var paymentId = UUID.randomUUID();
        when(paymentClient.pay(any(), any(), eq(accountId), anyLong(), any(), any())).thenReturn(paymentId);
        var requestId = "req-" + orderId;
        ticketing.checkout(orderId, accountId, "Bearer t", requestId);

        var envelope = EventEnvelope.of(PaymentFailed.TYPE, paymentId, 1, requestId, requestId,
                new PaymentFailed(paymentId, "PAYMENT_DECLINED", "AUTHORIZE", accountId, 2000, "GBP"));
        kafka.send(PaymentFailed.TOPIC, paymentId.toString(), json.writeValueAsString(envelope));

        await().untilAsserted(() -> assertThat(ticketing.order(orderId).orElseThrow().status()).isEqualTo("FAILED"));
        assertThat(seatStatus(event.id(), seat)).isEqualTo("FREE");
    }

    @Test
    void aHeldOrderExpiresAndCheckoutOfAnExpiredOrderIs409() {
        var event = ticketing.createEvent("Expiring Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-expire", 2000, "GBP", 1, 1);
        var seat = ticketing.event(event.id()).orElseThrow().seats().getFirst().seat();
        var accountId = UUID.randomUUID();
        var orderId = ticketing.hold(event.id(), accountId, "alice", List.of(seat));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(ticketing.order(orderId).orElseThrow().status()).isEqualTo("EXPIRED");
            assertThat(seatStatus(event.id(), seat)).isEqualTo("FREE");
        });

        assertThatThrownBy(() -> ticketing.checkout(orderId, accountId, "Bearer t", "req-expired"))
                .isInstanceOf(ErrorResponseException.class)
                .extracting(e -> ((ErrorResponseException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /** A 4xx gives the hold back; a payment made later under the same request id must not confirm it. */
    @Test
    void theRequestIdFallbackNeverTouchesAHeldOrder() {
        var event = ticketing.createEvent("Refused Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-refused", 2000, "GBP", 1, 1);
        var seat = ticketing.event(event.id()).orElseThrow().seats().getFirst().seat();
        var accountId = UUID.randomUUID();
        var orderId = keepHeld(ticketing.hold(event.id(), accountId, "alice", List.of(seat)));
        when(paymentClient.pay(any(), any(), eq(accountId), anyLong(), any(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        var requestId = "req-" + orderId;

        assertThatThrownBy(() -> ticketing.checkout(orderId, accountId, "Bearer t", requestId))
                .isInstanceOf(ErrorResponseException.class)
                .extracting(e -> ((ErrorResponseException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ticketing.order(orderId).orElseThrow().status()).isEqualTo("HELD");

        ticketing.confirm(UUID.randomUUID(), requestId, accountId, 2000, "GBP");
        assertThat(ticketing.order(orderId).orElseThrow().status()).isEqualTo("HELD");
        assertThat(seatStatus(event.id(), seat)).isEqualTo("HELD");
    }

    /**
     * No clean answer from payment-service: the order stays PENDING_PAYMENT and its outcome finds it by request id.
     * That id is the buyer's own header, so an outcome for another account or amount settles nothing.
     */
    @Test
    void anUnansweredCheckoutIsSettledByRequestIdOnlyByAMatchingOutcome() {
        var event = ticketing.createEvent("Reset Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-reset", 2000, "GBP", 1, 1);
        var seat = ticketing.event(event.id()).orElseThrow().seats().getFirst().seat();
        var accountId = UUID.randomUUID();
        var orderId = ticketing.hold(event.id(), accountId, "alice", List.of(seat));
        when(paymentClient.pay(any(), any(), eq(accountId), anyLong(), any(), any()))
                .thenThrow(new ResourceAccessException("connection reset"));
        var requestId = "req-" + orderId;

        var order = ticketing.checkout(orderId, accountId, "Bearer t", requestId);
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.paymentId()).isNull();

        ticketing.confirm(UUID.randomUUID(), requestId, accountId, 1, "GBP");                 // 1p for the whole order
        ticketing.confirm(UUID.randomUUID(), requestId, UUID.randomUUID(), 2000, "GBP");      // someone else's payment
        ticketing.fail(UUID.randomUUID(), requestId, UUID.randomUUID(), 2000, "GBP", "PAYMENT_DECLINED");
        assertThat(ticketing.order(orderId).orElseThrow().status()).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(event.id(), seat)).isEqualTo("HELD");

        var paymentId = UUID.randomUUID();
        ticketing.confirm(paymentId, requestId, accountId, 2000, "GBP");
        assertThat(ticketing.order(orderId).orElseThrow().paymentId()).isEqualTo(paymentId);
        assertThat(seatStatus(event.id(), seat)).isEqualTo("SOLD");
    }

    @Test
    void anUnansweredCheckoutWithNoOutcomeTimesOutAfterTenMinutes() {
        var event = ticketing.createEvent("Silent Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-silent", 2000, "GBP", 1, 1);
        var seat = ticketing.event(event.id()).orElseThrow().seats().getFirst().seat();
        var accountId = UUID.randomUUID();
        var orderId = ticketing.hold(event.id(), accountId, "alice", List.of(seat));
        when(paymentClient.pay(any(), any(), eq(accountId), anyLong(), any(), any()))
                .thenThrow(new ResourceAccessException("read timed out"));
        ticketing.checkout(orderId, accountId, "Bearer t", "req-" + orderId);

        db.sql("UPDATE orders SET updated_at = now() - interval '11 minutes' WHERE id = :id").param("id", orderId).update();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var order = ticketing.order(orderId).orElseThrow();
            assertThat(order.status()).isEqualTo("FAILED");
            assertThat(order.failReason()).isEqualTo("TIMED_OUT");
            assertThat(seatStatus(event.id(), seat)).isEqualTo("FREE");
        });
    }

    /** Cancel expires what nobody started paying for; an order already at payment-service waits for its outcome. */
    @Test
    void cancelLeavesAPendingPaymentOrdersSeatsHeld() {
        var event = ticketing.createEvent("Cancelled Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-cancel", 2000, "GBP", 1, 2);
        var paying = UUID.randomUUID();
        var payingOrder = ticketing.hold(event.id(), paying, "alice", List.of("A1"));
        var paymentId = UUID.randomUUID();
        when(paymentClient.pay(any(), any(), eq(paying), anyLong(), any(), any())).thenReturn(paymentId);
        ticketing.checkout(payingOrder, paying, "Bearer t", "req-" + payingOrder);
        var heldOrder = keepHeld(ticketing.hold(event.id(), UUID.randomUUID(), "bob", List.of("A2")));

        ticketing.cancelEvent(event.id());
        assertThat(ticketing.order(heldOrder).orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(seatStatus(event.id(), "A2")).isEqualTo("FREE");
        assertThat(ticketing.order(payingOrder).orElseThrow().status()).isEqualTo("PENDING_PAYMENT");
        assertThat(seatStatus(event.id(), "A1")).isEqualTo("HELD");

        ticketing.confirm(paymentId, "req-" + payingOrder, paying, 2000, "GBP");
        assertThat(seatStatus(event.id(), "A1")).isEqualTo("SOLD");
    }

    /** Cancel asks payment to refund each CONFIRMED order (and only those); the refund coming back frees the seat once. */
    @Test
    void cancelRefundsAConfirmedOrderAndTheRefundFreesItsSeat() {
        var event = ticketing.createEvent("Refund Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-refund", 2000, "GBP", 1, 2);
        var buyer = UUID.randomUUID();
        var paidOrder = ticketing.hold(event.id(), buyer, "alice", List.of("A1"));
        var paymentId = UUID.randomUUID();
        when(paymentClient.pay(any(), any(), eq(buyer), anyLong(), any(), any())).thenReturn(paymentId);
        ticketing.checkout(paidOrder, buyer, "Bearer t", "req-" + paidOrder);
        ticketing.confirm(paymentId, "req-" + paidOrder, buyer, 2000, "GBP");
        var heldOrder = keepHeld(ticketing.hold(event.id(), UUID.randomUUID(), "bob", List.of("A2")));
        assertThat(seatStatus(event.id(), "A1")).isEqualTo("SOLD");

        ticketing.cancelEvent(event.id());
        assertThat(ticketing.order(heldOrder).orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(refundCommands(paymentId)).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM outbox WHERE event_type = 'payment.RefundOrder'").query(Long.class).single())
                .isEqualTo(1);   // the HELD order asked for nothing
        assertThat(ticketing.order(paidOrder).orElseThrow().status()).isEqualTo("CONFIRMED");

        ticketing.refunded(paymentId, buyer, 2000, "GBP");
        assertThat(ticketing.order(paidOrder).orElseThrow().status()).isEqualTo("REFUNDED");
        assertThat(seatStatus(event.id(), "A1")).isEqualTo("FREE");

        ticketing.refunded(paymentId, buyer, 2000, "GBP");   // redelivery: the status guard makes it a no-op
        assertThat(ticketing.order(paidOrder).orElseThrow().status()).isEqualTo("REFUNDED");
        assertThat(refundCommands(paymentId)).isEqualTo(1);
    }

    private long refundCommands(UUID paymentId) {
        return db.sql("SELECT count(*) FROM outbox WHERE event_type = 'payment.RefundOrder' AND aggregate_id = :id")
                .param("id", paymentId).query(Long.class).single();
    }

    /** Five parallel holds from one account: the advisory lock lets exactly two open orders through. */
    @Test
    void aThirdOpenOrderForOneAccountIsRefused() throws Exception {
        var event = ticketing.createEvent("Greedy Show", null, "Venue", "City", "Music", Instant.now().plusSeconds(3600),
                "org-greedy", 2000, "GBP", 1, 5);
        var accountId = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(5);
        var go = new CountDownLatch(1);
        var created = new AtomicInteger();
        var tasks = IntStream.rangeClosed(1, 5).mapToObj(i -> pool.submit(() -> {
            go.await();
            try {
                ticketing.hold(event.id(), accountId, "alice", List.of("A" + i));
                created.incrementAndGet();
            } catch (ErrorResponseException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);   // rethrown by get() below if not
            }
            return null;
        })).toList();
        go.countDown();
        for (var t : tasks) t.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(created.get()).isEqualTo(2);
    }

    /** hold-ttl is 1s here; a test that needs the hold to outlive the sweep pushes its expiry out. */
    private UUID keepHeld(UUID orderId) {
        db.sql("UPDATE orders SET expires_at = now() + interval '1 hour' WHERE id = :id").param("id", orderId).update();
        return orderId;
    }

    private String seatStatus(UUID eventId, String seat) {
        return db.sql("SELECT status FROM seats WHERE event_id = :event AND seat = :seat")
                .param("event", eventId).param("seat", seat).query(String.class).single();
    }
}
