package io.ledgerflow.events.ledger;

import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;

import java.util.List;
import java.util.UUID;

/**
 * The hold is no longer open: released back to the wallet, or captured because the money moved.
 * Same shape as FundsHeld so a consumer can undo with the code it applied with. aggregateVersion 2.
 * Its own topic for the reason HoldRejected has one: the v1 hold topic's schema cannot widen.
 */
public record HoldClosed(
        UUID holdId,
        List<WalletRef> wallets,
        Money totalAmount,
        UUID reference,
        Outcome outcome) {

    public enum Outcome { RELEASED, CAPTURED }

    public static final String TYPE = "ledger.HoldClosed";
    public static final String TOPIC = "ledgerflow.ledger.hold-closed.events.v1";
}
