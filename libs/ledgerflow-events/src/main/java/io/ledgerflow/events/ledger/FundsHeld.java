package io.ledgerflow.events.ledger;

import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Event-carried state transfer: everything a consumer needs is here,
 * so nobody has to call ledger back to find out what happened.
 * Contract: docs/events/ledger.FundsHeld.md. Schema: resources/schemas/.
 */
public record FundsHeld(
        UUID holdId,
        List<WalletRef> wallets,
        Instant expiresAt,
        Money totalAmount,
        UUID reference) {   // what the hold is for (the payment); null for a hold placed over HTTP

    public static final String TYPE = "ledger.FundsHeld";
    public static final String TOPIC = "ledgerflow.ledger.wallet-hold.events.v1";
}
