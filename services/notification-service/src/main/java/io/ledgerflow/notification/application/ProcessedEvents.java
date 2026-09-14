package io.ledgerflow.notification.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProcessedEvents {

    private final JdbcClient db;

    ProcessedEvents(JdbcClient db) {
        this.db = db;
    }

    /**
     * The insert is both the check and the claim: true means first sighting, do the work; false means
     * already handled, skip. Joins the caller's transaction on purpose. Its own transaction would mark
     * the event done even when the work then fails, and the retry would skip it.
     */
    public boolean markProcessed(UUID eventId, String consumerGroup) {
        return db.sql("""
                INSERT INTO processed_events (event_id, consumer_group)
                VALUES (:eventId, :group)
                ON CONFLICT DO NOTHING
                """)
                .param("eventId", eventId)
                .param("group", consumerGroup)
                .update() == 1;
    }
}
