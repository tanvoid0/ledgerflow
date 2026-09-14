package io.ledgerflow.account.application;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID walletId) {
        super("Wallet " + walletId + " has insufficient funds");
    }
}
