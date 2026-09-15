package io.ledgerflow.ledger;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.events.ledger.HoldClosed;
import io.ledgerflow.events.ledger.HoldRejected;
import io.ledgerflow.events.ledger.ReleaseWallets;
import io.ledgerflow.events.ledger.ReserveWallets;
import io.ledgerflow.ledger.adapter.out.account.AccountClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/** The saga's side of ledger: a command on the topic becomes holds, a reply, or a rejection, and never a dead letter for a typo. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldCommandsIT {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;

    @MockitoBean AccountClient account;

    @BeforeEach
    void accountKnowsOneWallet() {
        when(account.account(ACCOUNT)).thenReturn(new AccountClient.AccountView(ACCOUNT, "Demo Arena",
                List.of(new AccountClient.WalletView(UUID.randomUUID(), "A-12"))));
    }

    @Test
    void reserveHoldsTheWalletsUnderTheReferenceAndReleaseLetsThemGo() {
        var payment = UUID.randomUUID();
        send(ReserveWallets.TYPE, payment, new ReserveWallets(payment, ACCOUNT, List.of("A-12"), Money.gbp(4500)));

        await().untilAsserted(() -> assertThat(statuses(payment)).containsExactly("HELD"));
        assertThat(outboxTypes(payment)).containsExactly(FundsHeld.TYPE);

        send(ReleaseWallets.TYPE, payment, new ReleaseWallets(payment));
        await().untilAsserted(() -> assertThat(statuses(payment)).containsExactly("RELEASED"));
        assertThat(outboxTypes(payment)).containsExactly(FundsHeld.TYPE, HoldClosed.TYPE);

        send(ReleaseWallets.TYPE, payment, new ReleaseWallets(payment));   // twice is harmless: nothing is open, nothing is said
        await().untilAsserted(() -> assertThat(statuses(payment)).containsExactly("RELEASED"));
        assertThat(outboxTypes(payment)).containsExactly(FundsHeld.TYPE, HoldClosed.TYPE);
    }

    @Test
    void anUnknownWalletIsAnsweredWithARejectionNotARetry() {
        var payment = UUID.randomUUID();
        send(ReserveWallets.TYPE, payment, new ReserveWallets(payment, ACCOUNT, List.of("ZZ-99"), Money.gbp(4500)));

        await().untilAsserted(() -> assertThat(outboxTypes(payment)).containsExactly(HoldRejected.TYPE));
        assertThat(statuses(payment)).isEmpty();
    }

    private void send(String type, UUID key, Object payload) {
        var envelope = EventEnvelope.of(type, key, 1, "req-1", "req-1", payload);
        kafka.send(ReserveWallets.TOPIC, key.toString(), json.writeValueAsString(envelope));
    }

    private List<String> statuses(UUID reference) {
        return db.sql("SELECT status FROM funds_holds WHERE reference = :ref").param("ref", reference)
                .query(String.class).list();
    }

    private List<String> outboxTypes(UUID reference) {
        return db.sql("SELECT event_type FROM outbox WHERE payload->'payload'->>'reference' = :ref ORDER BY occurred_at")
                .param("ref", reference.toString()).query(String.class).list();
    }
}
