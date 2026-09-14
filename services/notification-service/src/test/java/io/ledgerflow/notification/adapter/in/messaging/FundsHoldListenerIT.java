package io.ledgerflow.notification.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.notification.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.JacksonMapperUtils;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Bytes on a topic become a typed method call; failures are retried or dead-lettered, never dropped. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FundsHoldListenerIT {

    static final JsonMapper json = JacksonMapperUtils.enhancedJsonMapper();

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcClient db;

    @MockitoSpyBean
    FundsHoldListener listener;

    @Test
    void anEnvelopeOnTheTopicArrivesTypedAndIntact() {
        var sent = send(envelope(List.of(new WalletRef(UUID.randomUUID(), "A-12"))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventEnvelope<FundsHeld>> received = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(listener, timeout(10_000)).onFundsHeld(received.capture(), any());
        assertThat(received.getValue()).isEqualTo(sent);
        await().untilAsserted(() -> assertThat(sentFor(sent.aggregateId())).isOne());
    }

    @Test
    void aTransientFailureIsRetriedFromTheRetryTopic() {
        doThrow(new IllegalStateException("smtp down")).doCallRealMethod()
                .when(listener).onFundsHeld(any(), any());

        send(envelope(List.of(new WalletRef(UUID.randomUUID(), "A-12"))));

        verify(listener, timeout(10_000).times(2)).onFundsHeld(any(), any());
    }

    @Test
    void badDataSkipsTheRetriesAndLandsInTheDeadLetterTopic() {
        var sent = send(envelope(List.of()));   // no wallet: nothing to say to anyone

        var dead = deadLetter();
        assertThat(dead.key()).isEqualTo(sent.aggregateId().toString());
        assertThat(header(dead, KafkaHeaders.EXCEPTION_CAUSE_FQCN)).isEqualTo(InvalidPayloadException.class.getName());
        verify(listener, times(1)).onFundsHeld(any(), any());
    }

    @Test
    void unparseableBytesLandInTheDeadLetterTopic() {
        kafka.send(FundsHeld.TOPIC, "poison", "{not json");

        var dead = deadLetter();
        assertThat(dead.key()).isEqualTo("poison");
        assertThat(dead.value()).isEqualTo("{not json");
    }

    private int sentFor(UUID holdId) {
        return db.sql("SELECT count(*) FROM sent_notifications WHERE hold_id = :id")
                .param("id", holdId).query(Integer.class).single();
    }

    private ConsumerRecord<String, String> deadLetter() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ConsumerRecord<String, String>> dead = ArgumentCaptor.forClass(ConsumerRecord.class);
        verify(listener, timeout(10_000)).onDead(dead.capture(), any());
        return dead.getValue();
    }

    private EventEnvelope<FundsHeld> send(EventEnvelope<FundsHeld> envelope) {
        kafka.send(FundsHeld.TOPIC, envelope.aggregateId().toString(), json.writeValueAsString(envelope));
        return envelope;
    }

    private static EventEnvelope<FundsHeld> envelope(List<WalletRef> wallets) {
        var holdId = UUID.randomUUID();
        return EventEnvelope.of(FundsHeld.TYPE, holdId, 1, "req-1", "req-1",
                new FundsHeld(holdId, wallets, Instant.parse("2026-09-14T20:00:00Z"), Money.gbp(4500)));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value());
    }
}
