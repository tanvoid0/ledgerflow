package io.ledgerflow.account.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ledgerflow.account")
public record AccountProperties(
        @NotBlank String displayName,
        @Min(1) @Max(500) int maxWalletsPerAccount) {
}
