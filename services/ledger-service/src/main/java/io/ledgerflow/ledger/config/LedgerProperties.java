package io.ledgerflow.ledger.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "ledgerflow")
public record LedgerProperties(
        @NotBlank String accountBaseUrl,
        @NotNull @DefaultValue("500ms") Duration accountConnectTimeout,
        @NotNull @DefaultValue("800ms") Duration accountReadTimeout) {}
