package io.ledgerflow.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;

@Configuration
class KafkaConfig {

    /**
     * Bytes stay bytes until a listener says what type it wants. The converter infers the target from the
     * method parameter and ignores the producer's type header, which names a class this service does not have.
     */
    @Bean
    RecordMessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
