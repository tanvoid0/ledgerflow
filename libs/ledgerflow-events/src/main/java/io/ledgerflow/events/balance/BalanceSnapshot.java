package io.ledgerflow.events.balance;

import java.util.UUID;

/**
 * The wallet's state after an apply, not an event: consumers keep the highest version per wallet,
 * not the last record, since two apply threads can publish one wallet's snapshots in either order.
 */
public record BalanceSnapshot(
        UUID accountId,
        String label,
        String currency,
        long balanceMinor,
        long heldMinor,
        long version,
        Source source) {

    /** The record that produced this snapshot. */
    public record Source(String topic, int partition, long offset) {}

    public static final String TYPE = "balance.Snapshot";
    public static final String TOPIC = "ledgerflow.balance.snapshots.v1";
}
