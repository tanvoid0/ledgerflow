package io.ledgerflow.ledger.adapter.out.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.ledger.domain.model.FundsHold;
import io.ledgerflow.starter.web.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HoldEventPublisherTest {

    @SuppressWarnings("unchecked")
    KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    HoldEventPublisher publisher = new HoldEventPublisher(kafka);

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void keysByHoldIdAndCarriesEverythingAConsumerNeeds() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        var account = UUID.randomUUID();
        var hold = FundsHold.hold(account, "A-12", Money.gbp(4500), Instant.parse("2026-09-14T20:00:00Z"));

        publisher.publish(hold);

        var sent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(kafka).send(eq(FundsHeld.TOPIC), eq(hold.id().toString()), sent.capture());
        @SuppressWarnings("unchecked") EventEnvelope<FundsHeld> env = sent.getValue();
        assertThat(env.eventId()).isNotNull();
        assertThat(env.eventType()).isEqualTo("ledger.FundsHeld");
        assertThat(env.schemaVersion()).isEqualTo(1);
        assertThat(env.aggregateId()).isEqualTo(hold.id());
        assertThat(env.correlationId()).isEqualTo("req-42");
        assertThat(env.payload()).isEqualTo(new FundsHeld(hold.id(),
                List.of(new WalletRef(account, "A-12")), hold.expiresAt(), Money.gbp(4500)));
    }
}
