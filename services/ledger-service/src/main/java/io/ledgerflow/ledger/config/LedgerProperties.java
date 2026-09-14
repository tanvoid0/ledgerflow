package io.ledgerflow.ledger.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ledgerflow")
public record LedgerProperties(@NotBlank String accountBaseUrl) {}
