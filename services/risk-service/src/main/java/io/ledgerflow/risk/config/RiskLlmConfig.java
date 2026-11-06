package io.ledgerflow.risk.config;

import io.ledgerflow.llm.LlmClient;
import io.ledgerflow.llm.LlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Phase 2: one shared LLM client for the case Narrator and the analyst follow-up. */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
class RiskLlmConfig {

    @Bean
    LlmClient llmClient(LlmProperties props, JsonMapper json) {
        return new LlmClient(props, json);
    }
}
