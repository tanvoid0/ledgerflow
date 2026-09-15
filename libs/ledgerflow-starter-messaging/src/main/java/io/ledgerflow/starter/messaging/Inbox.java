package io.ledgerflow.starter.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.starter.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The consumer side of at-least-once: a message is handled once per consumer group, however often it arrives. */
public class Inbox {

    private static final Logger log = LoggerFactory.getLogger(Inbox.class);

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final String group;

    // the group from config, not from the record: a retry topic delivers under a different group name,
    // and a retry of the same event must still be a duplicate
    Inbox(JdbcClient db, TransactionTemplate tx, String group) {
        this.db = db;
        this.tx = tx;
        this.group = group;
    }

    /**
     * Mark and work commit together or not at all: were the mark its own transaction, it would stand
     * even when the work then failed, and the retry would skip the event. The insert is both the check
     * and the claim; the primary key on (event_id, consumer_group) is the whole dedupe logic.
     */
    public void once(EventEnvelope<?> event, Runnable work) {
        tx.executeWithoutResult(status -> {
            var claimed = db.sql("""
                    INSERT INTO processed_events (event_id, consumer_group)
                    VALUES (:eventId, :group)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("eventId", event.eventId())
                    .param("group", group)
                    .update() == 1;
            if (!claimed) {
                log.info("event {} already handled, skipping", event.eventId());
                return;
            }
            // the request that started the story: on every line the work logs, and on every event it appends
            if (event.correlationId() != null) MDC.put(RequestIdFilter.MDC_KEY, event.correlationId());
            try {
                work.run();
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        });
    }
}
