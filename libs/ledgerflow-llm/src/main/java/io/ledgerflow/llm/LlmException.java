package io.ledgerflow.llm;

/**
 * The platform was unreachable, returned nothing usable, or gave JSON that would not parse.
 * A feature catches this and degrades to its own deterministic fallback (ADR 0005 principle 3).
 */
public class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
