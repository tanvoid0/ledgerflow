package io.ledgerflow.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(
        /* how long a hold reserves its seats before the sweeper frees them */
        Duration holdTtl,
        /* how long an unanswered checkout waits for its outcome before the sweep fails it */
        Duration orphanTimeout,
        /* the one wallet every order pays from - a real storefront would let the buyer pick */
        String wallet,
        String paymentBaseUrl,
        String accountBaseUrl,
        /* seats per hold; also the seats generated for a new event's front rows fits the demo, not a real box office */
        int maxSeats) {}
