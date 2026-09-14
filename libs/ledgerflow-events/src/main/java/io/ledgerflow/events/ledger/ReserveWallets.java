package io.ledgerflow.events.ledger;

import io.ledgerflow.events.Money;

import java.util.List;
import java.util.UUID;

/** Place one hold of amount on each wallet, tagged with the reference. Replies: FundsHeld per hold, or HoldRejected. */
public record ReserveWallets(UUID reference, UUID accountId, List<String> wallets, Money amount) {

    public static final String TYPE = "ledger.ReserveWallets";
    public static final String TOPIC = "ledgerflow.ledger.hold.commands.v1";
}
