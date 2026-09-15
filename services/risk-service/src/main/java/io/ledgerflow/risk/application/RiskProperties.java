package io.ledgerflow.risk.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "risk")
public record RiskProperties(
        /* score at or above this and a human looks, even when no rule fired */
        double reviewThreshold) {}
