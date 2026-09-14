package io.ledgerflow.starter.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcClient db;
    private final KafkaTemplate<String, String> kafka;

    OutboxPublisher(JdbcClient db, KafkaTemplate<String, String> kafka) {
        this.db = db;
        this.kafka = kafka;
    }

    /**
     * Claim, send, mark: one transaction. SKIP LOCKED lets a second instance drain alongside this one
     * instead of queueing behind it. A broker that is down fails the send, the transaction rolls back
     * and the rows are still pending next tick. If the broker acks but the mark fails, the event goes
     * out twice: that is at-least-once, and the consumer's problem to solve (step 11).
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void drain() {
        var pending = db.sql("""
                SELECT id, aggregate_id, topic, payload
                  FROM outbox
                 WHERE published_at IS NULL
                 ORDER BY occurred_at
                 LIMIT 100
                   FOR UPDATE SKIP LOCKED
                """).query(Pending.class).list();
        if (pending.isEmpty()) return;

        // send the whole batch, then wait for every ack: a buffered send is not a delivered one
        var acks = pending.stream()
                .map(p -> kafka.send(p.topic(), p.aggregateId().toString(), p.payload()))
                .toList();
        acks.forEach(CompletableFuture::join);

        db.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .param("ids", pending.stream().map(Pending::id).toList())
                .update();
        log.debug("published {} event(s) from the outbox", pending.size());
    }

    record Pending(UUID id, UUID aggregateId, String topic, String payload) {}
}
