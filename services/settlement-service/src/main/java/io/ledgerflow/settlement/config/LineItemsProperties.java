package io.ledgerflow.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Which lineItemsStep gets built: today's single-threaded cursor, one of two threaded lessons, or the partitioned default. */
@Validated
@ConfigurationProperties(prefix = "ledgerflow.settlement")
public record LineItemsProperties(@DefaultValue("partition") Mode lineItems, @DefaultValue("8") int gridSize) {

    public enum Mode {SINGLE, THREADS_CURSOR, THREADS_PAGING, PARTITION}
}
