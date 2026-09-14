package io.ledgerflow.notification.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.ledger.FundsHeld;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** The whole idea in one test: bytes on a topic become a typed method call in this process. */
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
    void anEnvelopeOnTheTopicArrivesTypedAndIntact() {
        var holdId = UUID.randomUUID();
        var sent = EventEnvelope.of(FundsHeld.TYPE, holdId, 1, "req-1", "req-1",
                new FundsHeld(holdId, List.of(new WalletRef(UUID.randomUUID(), "A-12")),
                        Instant.parse("2026-09-14T20:00:00Z"), Money.gbp(4500)));

        kafka.send(FundsHeld.TOPIC, holdId.toString(), sent);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventEnvelope<FundsHeld>> received = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(listener, timeout(10_000)).onFundsHeld(received.capture());
        assertThat(received.getValue()).isEqualTo(sent);   // generic payload resolved from the parameter type
    }
}
