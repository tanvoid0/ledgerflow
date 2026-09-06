package io.ledgerflow.account.domain.model;

import java.util.List;
import java.util.UUID;

public record Account(UUID id, String name, List<Wallet> wallets) {
    public boolean hasWallet(String label) {
        return wallets.stream().anyMatch(s -> s.label().equals(label));
    }
}
