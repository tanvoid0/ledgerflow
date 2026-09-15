package io.ledgerflow.ledger;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.ledger.adapter.out.account.AccountClient;
import io.ledgerflow.ledger.application.PlaceHold;
import io.ledgerflow.ledger.domain.model.FundsHold;
import io.ledgerflow.ledger.domain.model.UnknownWalletException;
import io.ledgerflow.starter.web.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payoff for declaring the dependencies as types: replace two beans, never open a socket.
 * account-service is a mock; so is the Kafka producer, which is how "the broker is down" is
 * one line here and one container restart in docs/measurements/step-10-dual-write.md.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlaceHoldIT {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired PlaceHold placeHold;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;

    @MockitoBean AccountClient account;
    @MockitoBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void cleanTablesAndHealthyBroker() {
        db.sql("TRUNCATE outbox, funds_holds").update();
        brokerIs(CompletableFuture.completedFuture(null));
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
    }

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void refusesAWalletAccountDoesNotKnowAndWritesNothing() {
        accountHas("A-12");

        assertThatThrownBy(() -> placeHold.place(ACCOUNT, List.of("A-12", "ZZ-99"), Money.gbp(100), null))
                .isInstanceOf(UnknownWalletException.class)
                .hasMessageContaining("ZZ-99");

        assertThat(count("funds_holds")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void holdsAKnownWalletAndQueuesTheEventInTheSameTransaction() {
        accountHas("A-12");

        var hold = placeHold.place(ACCOUNT, List.of("A-12"), Money.gbp(4500), null).getFirst();

        assertThat(hold.status()).isEqualTo(FundsHold.Status.HELD);
        assertThat(db.sql("SELECT status FROM funds_holds WHERE id = :id").param("id", hold.id())
                .query(String.class).single()).isEqualTo("HELD");

        var stored = db.sql("SELECT payload FROM outbox WHERE aggregate_id = :id").param("id", hold.id())
                .query(String.class).single();
        var event = json.readValue(stored, new TypeReference<EventEnvelope<FundsHeld>>() {});
        assertThat(event.eventType()).isEqualTo("ledger.FundsHeld");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.correlationId()).isEqualTo("req-42");
        assertThat(event.payload()).isEqualTo(new FundsHeld(hold.id(),
                List.of(new WalletRef(ACCOUNT, "A-12")), hold.expiresAt(), Money.gbp(4500), null));

        var partitionKey = db.sql("SELECT partition_key FROM outbox WHERE aggregate_id = :id").param("id", hold.id())
                .query(String.class).single();
        assertThat(partitionKey).isEqualTo(ACCOUNT + ":A-12");

        // the poller sends exactly the stored bytes, keyed by the wallet, and only then marks the row
        verify(kafka, timeout(5_000)).send(FundsHeld.TOPIC, partitionKey, stored);
        await().atMost(Duration.ofSeconds(5)).until(() -> pending() == 0);
    }

    @Test
    void rollsTheEventBackWithTheHold() {
        var tooLongForTheColumn = "A-" + "9".repeat(20);
        accountHas(tooLongForTheColumn);

        assertThatThrownBy(() -> placeHold.place(ACCOUNT, List.of(tooLongForTheColumn), Money.gbp(100), null))
                .isInstanceOf(DataAccessException.class);

        // nothing was said about a hold that does not exist
        assertThat(count("funds_holds")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void keepsTheEventUntilTheBrokerTakesIt() {
        accountHas("A-12");
        brokerIs(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        placeHold.place(ACCOUNT, List.of("A-12"), Money.gbp(100), null);

        verify(kafka, timeout(5_000).atLeastOnce()).send(eq(FundsHeld.TOPIC), eq(ACCOUNT + ":A-12"), anyString());
        assertThat(pending()).isEqualTo(1);   // tried, failed, still there

        brokerIs(CompletableFuture.completedFuture(null));
        await().atMost(Duration.ofSeconds(5)).until(() -> pending() == 0);
    }

    private void accountHas(String walletLabel) {
        when(account.account(ACCOUNT)).thenReturn(new AccountClient.AccountView(ACCOUNT, "Demo Arena",
                List.of(new AccountClient.WalletView(UUID.randomUUID(), walletLabel))));
    }

    private void brokerIs(CompletableFuture<SendResult<String, String>> answer) {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(answer);
    }

    private long count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long pending() {
        return db.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }
}
