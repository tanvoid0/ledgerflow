package io.ledgerflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "ledgerflow")
public record PaymentProperties(
        /* how long any one step may wait for its reply before the sweeper gives up on it */
        @DefaultValue("15s") Duration stepDeadline) {}
