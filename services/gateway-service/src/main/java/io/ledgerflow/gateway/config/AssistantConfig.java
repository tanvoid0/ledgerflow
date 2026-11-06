package io.ledgerflow.gateway.config;

import io.ledgerflow.llm.LlmClient;
import io.ledgerflow.llm.LlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, AssistantProperties.class})
class AssistantConfig {

    @Bean
    LlmClient llmClient(LlmProperties props, JsonMapper json) {
        return new LlmClient(props, json);
    }

    /** Points back at the gateway's own API; each request carries the caller's bearer, set per call. */
    @Bean
    RestClient ledgerApi(AssistantProperties props) {
        return RestClient.builder().baseUrl(props.gatewayBase()).build();
    }
}
