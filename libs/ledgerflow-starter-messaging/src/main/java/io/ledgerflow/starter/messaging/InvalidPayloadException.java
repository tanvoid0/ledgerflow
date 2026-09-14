package io.ledgerflow.starter.messaging;

/** The message will never be right, so do not retry it: straight to the dead letter topic. */
public class InvalidPayloadException extends RuntimeException {
    public InvalidPayloadException(String message) {
        super(message);
    }
}
