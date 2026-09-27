package io.ledgerflow.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(
        /* how long a hold reserves its seats before the sweeper frees them */
        Duration holdTtl,
        /* the one wallet every order pays from - a real storefront would let the buyer pick */
        String wallet,
        String paymentBaseUrl,
        /* seats per hold; also the seats generated for a new event's front rows fits the demo, not a real box office */
        int maxSeats) {}
