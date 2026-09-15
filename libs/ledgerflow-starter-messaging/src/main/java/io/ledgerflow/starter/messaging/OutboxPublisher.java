package io.ledgerflow.starter.messaging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};
    private static final int BATCH = 100;

    private final JdbcClient db;
    private final JsonMapper json;
    private final KafkaTemplate<String, String> kafka;
    private final ObservationRegistry observations;
    private final TransactionTemplate tx;

    OutboxPublisher(JdbcClient db, JsonMapper json, KafkaTemplate<String, String> kafka, ObservationRegistry observations,
                    TransactionTemplate tx) {
        this.db = db;
        this.json = json;
        this.kafka = kafka;
        this.observations = observations;
        this.tx = tx;
    }

    /**
     * Batches until one comes back short. One batch per tick puts a ceiling of batch/interval rows a
     * second on the whole service, and a backlog above it never shrinks: at 200 payments/s that ceiling
     * was the step 15 baseline. The tick is 50ms because a payment crosses seven outboxes, and at 500ms
     * the waiting alone was 2.4s of every payment. An empty poll costs the database 50 microseconds.
     */
    @Scheduled(fixedDelay = 50)
    public void drain() {
        int published;
        do {
            published = tx.execute(status -> publishBatch());
        } while (published == BATCH);
    }

    /**
     * Claim, send, mark: one transaction. SKIP LOCKED lets a second instance drain alongside this one
     * instead of queueing behind it. A broker that is down fails the send, the transaction rolls back
     * and the rows are still pending next tick. If the broker acks but the mark fails, the event goes
     * out twice: that is at-least-once, and the consumer's problem to solve (step 11).
     */
    private int publishBatch() {
        var pending = db.sql("""
                SELECT id, aggregate_id, topic, event_type, payload, trace_context
                  FROM outbox
                 WHERE published_at IS NULL
                 ORDER BY occurred_at
                 LIMIT :batch
                   FOR UPDATE SKIP LOCKED
                """).param("batch", BATCH).query(Pending.class).list();
        if (pending.isEmpty()) return 0;

        // send the whole batch, then wait for every ack: a buffered send is not a delivered one
        var acks = pending.stream().map(this::send).toList();
        acks.forEach(CompletableFuture::join);

        db.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .param("ids", pending.stream().map(Pending::id).toList())
                .update();
        log.debug("published {} event(s) from the outbox", pending.size());
        return pending.size();
    }

    /** Sent inside the trace the row was written in, so the Kafka hop hangs off the request that caused it, not off the timer. */
    private CompletableFuture<SendResult<String, String>> send(Pending p) {
        var trace = new ReceiverContext<Map<String, String>>(Map::get);
        trace.setCarrier(json.readValue(p.traceContext(), HEADERS));
        return Observation.createNotStarted("outbox.publish", () -> trace, observations)
                .contextualName("publish " + p.eventType())
                .observe(() -> kafka.send(p.topic(), p.aggregateId().toString(), p.payload()));
    }

    record Pending(UUID id, UUID aggregateId, String topic, String eventType, String payload, String traceContext) {}
}
