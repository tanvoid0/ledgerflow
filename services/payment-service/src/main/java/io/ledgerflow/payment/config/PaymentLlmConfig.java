package io.ledgerflow.payment.config;

import io.ledgerflow.llm.LlmClient;
import io.ledgerflow.llm.LlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Phase 3: the shared LLM client for the async payment/refund enrichment. */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
class PaymentLlmConfig {

    @Bean
    LlmClient llmClient(LlmProperties props, JsonMapper json) {
        return new LlmClient(props, json);
    }
}
