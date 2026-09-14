package io.ledgerflow.ledger.adapter.out.persistence;

import io.ledgerflow.events.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
public class OutboxAppender {

    private final JdbcClient db;
    private final JsonMapper json;

    /** Joins the caller's transaction. No @Transactional here on purpose: on its own this row means nothing. */
    public void append(String topic, EventEnvelope<?> event) {
        db.sql("""
                INSERT INTO outbox (id, aggregate_id, topic, event_type, payload, occurred_at)
                VALUES (:id, :aggregateId, :topic, :eventType, CAST(:payload AS jsonb), :occurredAt)
                """)
                .param("id", event.eventId())
                .param("aggregateId", event.aggregateId())
                .param("topic", topic)
                .param("eventType", event.eventType())
                .param("payload", json.writeValueAsString(event))
                .param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))   // pgjdbc has no Instant
                .update();
    }
}
