package io.ledgerflow.account.domain.model;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID id) {
        super("No account with id " + id);
    }
}
