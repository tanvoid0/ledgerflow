package io.ledgerflow.notification.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.notification.TestcontainersConfiguration;
import io.ledgerflow.notification.application.Notifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.JacksonMapperUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** The outbox delivers at least once. Every delivery after the first must change nothing. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReplayIdempotencyIT {

    static final JsonMapper json = JacksonMapperUtils.enhancedJsonMapper();

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcClient db;

    @MockitoSpyBean
    FundsHoldListener listener;

    @MockitoSpyBean
    Notifier notifier;

    @Test
    void replayingTheTopicSendsNothingTwice() {
        var events = Stream.generate(ReplayIdempotencyIT::envelope).limit(100).toList();

        events.forEach(this::send);
        await().untilAsserted(() -> assertThat(sentFor(events)).isEqualTo(100));

        events.forEach(this::send);   // what a rewound group delivers: the same events, the same ids
        verify(listener, timeout(10_000).times(200)).onFundsHeld(any(), any());
        assertThat(sentFor(events)).isEqualTo(100);
    }

    @Test
    void aFailureAfterTheMarkRollsTheMarkBackSoTheRetryDoesTheWork() {
        doThrow(new IllegalStateException("smtp down")).doCallRealMethod().when(notifier).send(any());

        var event = envelope();
        send(event);

        // were the mark its own transaction, the retry would find the event "done" and this would stay 0
        await().untilAsserted(() -> assertThat(sentFor(List.of(event))).isEqualTo(1));
        verify(listener, timeout(10_000).times(2)).onFundsHeld(any(), any());
    }

    private int sentFor(List<EventEnvelope<FundsHeld>> events) {
        return db.sql("SELECT count(*) FROM sent_notifications WHERE hold_id IN (:ids)")
                .param("ids", events.stream().map(EventEnvelope::aggregateId).toList())
                .query(Integer.class).single();
    }

    private void send(EventEnvelope<FundsHeld> envelope) {
        kafka.send(FundsHeld.TOPIC, envelope.aggregateId().toString(), json.writeValueAsString(envelope));
    }

    private static EventEnvelope<FundsHeld> envelope() {
        var holdId = UUID.randomUUID();
        return EventEnvelope.of(FundsHeld.TYPE, holdId, 1, "req-1", "req-1",
                new FundsHeld(holdId, List.of(new WalletRef(UUID.randomUUID(), "A-12")),
                        Instant.parse("2026-09-14T20:00:00Z"), Money.gbp(4500), null));
    }
}
