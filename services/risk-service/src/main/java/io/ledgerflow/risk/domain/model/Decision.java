package io.ledgerflow.risk.domain.model;

/** What risk-service decided about a payment, and why: a rule that fired, or a score that crossed the threshold. */
public sealed interface Decision {

    record Allow() implements Decision {}

    record Review(String reason) implements Decision {}

    record Block(String rule) implements Decision {}
}
