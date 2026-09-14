package io.ledgerflow.notification.adapter.in.messaging;

/** The message itself is wrong. Retrying cannot fix it, so it goes straight to the dead letter topic. */
class InvalidPayloadException extends RuntimeException {
    InvalidPayloadException(String message) { super(message); }
}
