package io.ledgerflow.risk.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "risk.narrator")
public record NarratorProperties(
        /* base URL of an OpenAI-compatible /chat/completions endpoint; blank = template notes only, no network call */
        String url,
        String model,
        /* blank locally; set for a hosted provider that needs a bearer token */
        String apiKey,
        /* REVIEW decisions written to a risk_case per tick; the LLM call is the slow part, not the query */
        int batch,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("800") int maxTokens,
        /* older than this, a case note helps no one; keeps a load test's REVIEW backlog off the GPU for hours */
        @DefaultValue("10m") Duration maxAge) {}
