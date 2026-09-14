package io.ledgerflow.account.domain.model;

public class UnbalancedEntryException extends RuntimeException {
    public UnbalancedEntryException(String message) {
        super(message);
    }
}
