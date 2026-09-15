package io.ledgerflow.starter.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/** Writes the envelope into the service's outbox table; the poller takes it from there. */
public class OutboxAppender {

    private final JdbcClient db;
    private final JsonMapper json;
    private final ObservationRegistry observations;

    OutboxAppender(JdbcClient db, JsonMapper json, ObservationRegistry observations) {
        this.db = db;
        this.json = json;
        this.observations = observations;
    }

    /**
     * Joins the caller's transaction. No @Transactional here on purpose: on its own this row means nothing.
     * The row also remembers the trace it was written in: the poller thread has none of its own, and without
     * this every trace would end here, one hop short of the broker.
     */
    public void append(String topic, EventEnvelope<?> event) {
        var trace = new SenderContext<Map<String, String>>(Map::put);
        trace.setCarrier(new HashMap<>());
        Observation.createNotStarted("outbox.append", () -> trace, observations)
                .contextualName("outbox " + event.eventType())
                .observe(() -> db.sql("""
                        INSERT INTO outbox (id, aggregate_id, topic, event_type, payload, occurred_at, trace_context)
                        VALUES (:id, :aggregateId, :topic, :eventType, CAST(:payload AS jsonb), :occurredAt, CAST(:trace AS jsonb))
                        """)
                        .param("id", event.eventId())
                        .param("aggregateId", event.aggregateId())
                        .param("topic", topic)
                        .param("eventType", event.eventType())
                        .param("payload", json.writeValueAsString(event))
                        .param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))   // pgjdbc has no Instant
                        .param("trace", json.writeValueAsString(trace.getCarrier()))
                        .update());
    }
}
