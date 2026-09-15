package io.ledgerflow.starter.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.support.EndpointHandlerMethod;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every service that talks over Kafka gets the same plumbing: an outbox poller, an inbox that dedupes
 * on eventId, and retry topics that end in a dead letter topic. The service brings the two tables.
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, JdbcClientAutoConfiguration.class})
@EnableKafkaRetryTopic
@EnableScheduling   // the poller, and the retry delays need a TaskScheduler
public class MessagingAutoConfiguration {

    /**
     * Bytes stay bytes until a listener says what type it wants. The converter infers the target from the
     * method parameter and ignores the producer's type header, which names a class this service may not have.
     */
    @Bean
    @ConditionalOnMissingBean
    RecordMessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    DeadLetters deadLetters(MeterRegistry meters) {
        return new DeadLetters(meters);
    }

    /**
     * Five listener containers per service (main, three retry tiers, dlt) share one consumer factory,
     * so a plain yml group.instance.id gives them all the same static id and the broker fences all but
     * the first. Suffixing the listener id keeps it unique per JVM and stable across a restart.
     * Object, Object on purpose: Boot's listener-factory configurer asks the ObjectProvider for exactly
     * that type, and a String, String customizer is never picked up.
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.kafka.consumer.properties", name = "group.instance.id")
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> staticMembershipPerContainer(KafkaProperties props) {
        var groupInstanceId = props.getConsumer().getProperties().get("group.instance.id");
        return container -> {
            // retry-topic listener ids carry a '#', which group.instance.id rejects
            var suffix = container.getListenerId().replaceAll("[^A-Za-z0-9._-]", "_");
            container.getContainerProperties().getKafkaConsumerProperties()
                    .setProperty("group.instance.id", groupInstanceId + "-" + suffix);
        };
    }

    /**
     * One retry policy for every listener in the service: 1s, 3s, 9s on non-blocking retry topics, then the DLT.
     * The suffix carries the consumer group. Two services reading the same topic would otherwise share
     * retry topics and replay each other's failures.
     */
    @Bean
    RetryTopicConfiguration retryTopics(KafkaTemplate<String, String> kafka, KafkaProperties props) {
        var group = props.getConsumer().getGroupId();
        return RetryTopicConfigurationBuilder.newInstance()
                .maxAttempts(4)
                .exponentialBackoff(1000, 3.0, 9000)
                .retryTopicSuffix("." + group + ".retry")
                .dltSuffix("." + group + ".dlt")
                .autoCreateTopicsWith(3, (short) 1)
                .notRetryOn(InvalidPayloadException.class)   // bad data never gets better; skip the retries
                .dltHandlerMethod(new EndpointHandlerMethod(DeadLetters.class, "onDead"))
                .create(kafka);
    }

    /**
     * The database half, only where there is a database. A projection that lives in Redis has no outbox
     * and no inbox table, and must not be made to configure a DataSource it will never open.
     * Nested and guarded on the class so the outer configuration never mentions JdbcClient in a signature.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcClient.class)
    @ConditionalOnBean(JdbcClient.class)
    static class Database {

        // a consumer-only service (risk-service) has nothing to tell anyone: no outbox row, ever, from any code path in it
        @Bean
        @ConditionalOnProperty(prefix = "ledgerflow.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
        OutboxAppender outboxAppender(JdbcClient db, JsonMapper json, ObservationRegistry observations) {
            return new OutboxAppender(db, json, observations);
        }

        @Bean
        @ConditionalOnProperty(prefix = "ledgerflow.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
        OutboxPublisher outboxPublisher(JdbcClient db, JsonMapper json, KafkaTemplate<String, String> kafka, ObservationRegistry observations,
                                        TransactionTemplate tx) {
            return new OutboxPublisher(db, json, kafka, observations, tx);
        }

        @Bean
        Inbox inbox(JdbcClient db, TransactionTemplate tx, KafkaProperties kafka) {
            return new Inbox(db, tx, kafka.getConsumer().getGroupId());
        }
    }
}
