package io.ledgerflow.settlement.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.UUID;

@Validated
@ConfigurationProperties(prefix = "ledgerflow")
public record SettlementProperties(
        @NotBlank String accountBaseUrl,
        @NotNull UUID settlementWalletId,   // where captured money lands
        @NotNull @DefaultValue("500ms") Duration accountConnectTimeout,
        @NotNull @DefaultValue("800ms") Duration accountReadTimeout,
        @NotBlank String issuerBaseUrl) {}
