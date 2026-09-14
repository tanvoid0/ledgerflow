package io.ledgerflow.ledger.domain.model;

import java.util.UUID;

public class UnknownWalletException extends RuntimeException {
    public UnknownWalletException(UUID accountId, String walletLabel) {
        super("Account " + accountId + " has no wallet " + walletLabel);
    }
}
