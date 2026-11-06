package io.ledgerflow.risk.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The batch-schedule knobs for the case Narrator. The LLM client itself (url / model / key / tokens /
 * timeout) is configured once under {@code llm.*} and shared with the analyst follow-up.
 */
@ConfigurationProperties(prefix = "risk.narrator")
public record NarratorProperties(
        /* REVIEW decisions written to a risk_case per tick; the LLM call is the slow part, not the query */
        int batch,
        /* older than this, a case note helps no one; keeps a load test's REVIEW backlog off the GPU for hours */
        @DefaultValue("10m") Duration maxAge) {}
