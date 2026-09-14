package io.ledgerflow.payment.domain.model;

/** The sweeper's reply on behalf of a service that never answered. Handled by the same switch as a real one. */
public record StepTimedOut(PaymentState.Step step) {}
