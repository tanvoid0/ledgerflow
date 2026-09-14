package io.ledgerflow.account.domain.model;

import java.util.List;
import java.util.UUID;

/** A customer with wallets. Plain record: nothing here knows about HTTP or SQL. */
public record Account(UUID id, String name, List<Wallet> wallets) {

    public boolean hasWallet(String label) {
        return wallets.stream().anyMatch(w -> w.label().equals(label));
    }
}
