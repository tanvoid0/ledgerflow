package io.ledgerflow.settlement.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Where the statement export writes its CSVs; the three cron strings are read directly off this same prefix by BatchSchedule. */
@Validated
@ConfigurationProperties(prefix = "settlement.batch")
public record BatchProperties(@NotBlank @DefaultValue("target/exports") String exportDir) {}
