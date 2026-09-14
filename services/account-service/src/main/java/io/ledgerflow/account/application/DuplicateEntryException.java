package io.ledgerflow.account.application;

public class DuplicateEntryException extends RuntimeException {
    public DuplicateEntryException(String key) {
        super("Idempotency key already used: " + key);
    }
}
