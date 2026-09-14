package io.ledgerflow.notification.adapter.in.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** The whole idea in one test: bytes on a topic become a method call in this process. */
@Testcontainers
@SpringBootTest(properties = "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer")
class FundsHoldListenerIT {

    @Container
    @ServiceConnection
    static RedpandaContainer redpanda = new RedpandaContainer("redpandadata/redpanda:v25.2.1");

    @Autowired
    KafkaTemplate<String, Object> kafka;

    @MockitoSpyBean
    FundsHoldListener listener;

    @Test
    void aMessageOnTheTopicReachesTheListener() {
        var event = new FundsHoldListener.FundsHeld(UUID.randomUUID(), UUID.randomUUID(), List.of("A-12"), Instant.now());

        kafka.send("wallet-hold-events", event.holdId().toString(), event);

        verify(listener, timeout(10_000)).onFundsHeld(any());
    }
}
