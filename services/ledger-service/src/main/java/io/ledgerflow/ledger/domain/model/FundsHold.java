package io.ledgerflow.ledger.domain.model;

import io.ledgerflow.events.Money;

import java.time.Instant;
import java.util.UUID;

/** A reservation of funds against one wallet. Money does not move until capture asks account to move it. */
public record FundsHold(UUID id, UUID accountId, String walletCode, Money amount, Status status, Instant expiresAt) {

    public enum Status { HELD, RELEASED, CAPTURED }

    public static FundsHold hold(UUID accountId, String walletCode, Money amount, Instant expiresAt) {
        return new FundsHold(UUID.randomUUID(), accountId, walletCode, amount, Status.HELD, expiresAt);
    }
}
