package io.ledgerflow.payment.application;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.payment.PaymentRequested;
import io.ledgerflow.payment.config.PaymentProperties;
import io.ledgerflow.payment.domain.model.PaymentState;
import io.ledgerflow.payment.domain.model.PaymentState.Failed;
import io.ledgerflow.payment.domain.model.StepTimedOut;
import io.ledgerflow.starter.messaging.OutboxAppender;
import io.ledgerflow.starter.web.RequestIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the saga: loads a payment's state, asks {@link PaymentSaga} what happens next, stores the
 * answer and queues its commands, all in one transaction. The row lock on the saga serialises
 * replies that arrive together; the outbox makes the commands as durable as the state change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Payments {

    private final JdbcClient db;
    private final JsonMapper json;
    private final OutboxAppender outbox;
    private final PaymentProperties props;
    private final MeterRegistry meters;

    @Transactional
    public PaymentState start(UUID accountId, List<String> wallets, Money amount, String beneficiary) {
        var decision = PaymentSaga.start(accountId, wallets, amount);
        var state = decision.next();
        var correlationId = MDC.get(RequestIdFilter.MDC_KEY);
        db.sql("""
                INSERT INTO sagas (payment_id, state, payload, current_step, deadline_at, correlation_id)
                VALUES (:id, :state, CAST(:payload AS jsonb), :step, :deadline, :correlationId)
                """)
                .param("id", state.paymentId()).param("state", name(state)).param("payload", json.writeValueAsString(state))
                .param("step", state.step().name()).param("deadline", deadline()).param("correlationId", correlationId)
                .update();
        send(state.paymentId(), decision.commands(), correlationId, correlationId);
        // risk-service scores every payment; it has no place in the saga's own reply flow, so it goes out on its own topic
        outbox.append(PaymentRequested.TOPIC, EventEnvelope.of(PaymentRequested.TYPE, state.paymentId(), 1, correlationId, correlationId,
                new PaymentRequested(state.paymentId(), accountId, wallets, amount, beneficiary)));
        return state;
    }

    /** A reply from a service, or a timeout from the sweeper: same door. Unknown payment: not ours, ignore. */
    @Transactional
    public void apply(UUID paymentId, Record reply, EventEnvelope<?> cause) {
        try (var _ = MDC.putCloseable("paymentId", paymentId.toString())) {   // every line below is findable by payment
            var row = db.sql("SELECT payload, correlation_id FROM sagas WHERE payment_id = :id FOR UPDATE")
                    .param("id", paymentId).query(Row.class).optional().orElse(null);
            if (row == null) {
                log.warn("{} for payment {} nobody here started; ignoring", reply.getClass().getSimpleName(), paymentId);
                return;
            }
            var state = json.readValue(row.payload(), PaymentState.class);
            var decision = PaymentSaga.on(state, reply);
            if (decision.next().equals(state)) {
                log.info("{} changes nothing for payment {} in {}", reply.getClass().getSimpleName(), paymentId, name(state));
                return;
            }
            var next = decision.next();
            log.info("payment {}: {} + {} -> {}", paymentId, name(state), reply.getClass().getSimpleName(), name(next));
            db.sql("""
                    UPDATE sagas SET state = :state, payload = CAST(:payload AS jsonb), current_step = :step,
                                     deadline_at = :deadline, updated_at = now()
                     WHERE payment_id = :id
                    """)
                    .param("id", paymentId).param("state", name(next)).param("payload", json.writeValueAsString(next))
                    .param("step", next.step() == null ? null : next.step().name())
                    .param("deadline", next.step() == null ? null : deadline())
                    .update();
            send(paymentId, decision.commands(), row.correlationId(), cause == null ? null : cause.eventId().toString());
            // the number that says customers are being turned away, and why: worth more than any latency histogram
            if (next instanceof Failed f) {
                meters.counter("ledgerflow.saga.compensated", "step", f.failedAt().name(), "reason", f.reason().name()).increment();
            }
        }
    }

    /** A step past its deadline is answered with a timeout, through the same transition as a real reply. */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void sweep() {
        var expired = db.sql("""
                SELECT payment_id, current_step FROM sagas
                 WHERE deadline_at < now() AND state NOT IN ('Captured', 'Failed')
                   FOR UPDATE SKIP LOCKED
                """).query(Expired.class).list();
        for (var e : expired) {
            log.warn("payment {} waited too long at {}", e.paymentId(), e.currentStep());
            apply(e.paymentId(), new StepTimedOut(PaymentState.Step.valueOf(e.currentStep())), null);
        }
    }

    public Optional<PaymentState> find(UUID paymentId) {
        return db.sql("SELECT payload FROM sagas WHERE payment_id = :id").param("id", paymentId)
                .query(String.class).optional().map(p -> json.readValue(p, PaymentState.class));
    }

    private void send(UUID paymentId, List<PaymentSaga.Command> commands, String correlationId, String causationId) {
        for (var c : commands) {
            outbox.append(c.topic(), EventEnvelope.of(c.type(), paymentId, 1, correlationId, causationId, c.payload()));
        }
    }

    private OffsetDateTime deadline() {
        return Instant.now().plus(props.stepDeadline()).atOffset(ZoneOffset.UTC);
    }

    private static String name(PaymentState s) {
        return s.getClass().getSimpleName();
    }

    record Row(String payload, String correlationId) {}

    record Expired(UUID paymentId, String currentStep) {}
}
