package io.ledgerflow.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/** Spring Framework 7 ships @Retryable in the core; this one switch enables it. No spring-retry. */
@Configuration
@EnableResilientMethods
class ResilienceConfig {}
