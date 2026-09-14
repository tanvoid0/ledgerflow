package io.ledgerflow.events;

import java.time.Instant;
import java.util.UUID;

/**
 * What every event carries regardless of type. The business fact goes in {@code payload};
 * everything else is here so consumers can dedupe, order and trace without knowing the type.
 */
public record EventEnvelope<T>(
        UUID eventId,          // unique per event: the idempotency key (step 11)
        String eventType,      // "ledger.FundsHeld"
        int schemaVersion,
        UUID aggregateId,      // also the partition key
        long aggregateVersion, // lets a consumer spot out-of-order arrivals
        Instant occurredAt,
        String correlationId,  // the user request that started all this
        String causationId,    // the message that directly caused this one
        T payload) {

    public static <T> EventEnvelope<T> of(String type, UUID aggregateId, long version,
                                          String correlationId, String causationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, 1, aggregateId, version,
                Instant.now(), correlationId, causationId, payload);
    }

    /** An answer to a message: same correlation, and the message answered is the cause. */
    public static <T> EventEnvelope<T> inReplyTo(EventEnvelope<?> cause, String type, UUID aggregateId, long version, T payload) {
        return of(type, aggregateId, version, cause.correlationId(), cause.eventId().toString(), payload);
    }
}
