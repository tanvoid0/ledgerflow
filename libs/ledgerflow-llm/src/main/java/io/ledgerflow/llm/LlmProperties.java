package io.ledgerflow.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Config for the local agent platform. One prefix per service — a service talks to one platform.
 * The legacy {@code risk.narrator} props are left untouched; new LLM features bind {@code llm.*}.
 */
@ConfigurationProperties("llm")
public record LlmProperties(
        /* base URL of the OpenAI-compatible platform, e.g. http://127.0.0.1:18410 */
        String url,
        /* preferred model; discovery picks it (or a tag variant like gemma4:e4b) if present, else the first offered */
        String model,
        /* mandatory against the real platform (Bearer); a secret, carried per environment, never committed */
        String apiKey,
        @DefaultValue("30s") Duration timeout,
        /* generous: the reasoning model spends tokens thinking before it answers, a tight budget returns empty content */
        @DefaultValue("2048") int maxTokens,
        /* Host header override: left blank the request's Host is the url's authority. Set it when the platform is
           loopback-bound and reached over a different name (a container hitting host.docker.internal:18410 must send
           Host: localhost:18410, which the platform's allowlist answers for). Needs -Djdk.httpclient.allowRestrictedHeaders=host. */
        @DefaultValue("") String hostHeader) {}
