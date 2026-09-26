package io.ledgerflow.account;

import io.ledgerflow.account.application.InsufficientFundsException;
import io.ledgerflow.account.application.PostTransfer;
import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.account.EntryPosted;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every entry in the book is also an event in the outbox: the ones posted from now on, and the ones before the topic existed. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@WithMockUser(roles = "ledger-write")
class PostTransferIT {

    @Autowired PostTransfer transfer;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;

    @Test
    void aTransferLeavesOneEntryPostedWithBothLines() {
        var entry = transfer.transfer("t-1", walletId("A-1"), walletId("A-2"), Money.gbp(2500), "lunch");

        var event = event(entry.id());
        assertThat(event.eventType()).isEqualTo(EntryPosted.TYPE);
        assertThat(event.payload().lines()).extracting(l -> l.wallet().label() + " " + l.amount().minorUnits())
                .containsExactly("A-1 -2500", "A-2 2500");
    }

    @Test
    void aRefusedTransferSaysNothing() {
        assertThatThrownBy(() -> transfer.transfer("t-2", walletId("A-3"), walletId("A-4"), Money.gbp(999_999), "too much"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(db.sql("SELECT count(*) FROM outbox WHERE payload->'payload'->>'description' = 'too much'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void theOpeningBalancesWereBackfilledIntoTheOutbox() {
        var seed = event(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

        assertThat(seed.payload().lines()).hasSize(21);
        assertThat(seed.payload().lines()).extracting(l -> l.amount().minorUnits()).contains(10000L, -200_000L);
    }

    private EventEnvelope<EntryPosted> event(UUID entryId) {
        var payload = db.sql("SELECT payload::text FROM outbox WHERE aggregate_id = :id").param("id", entryId)
                .query(String.class).single();
        return json.readValue(payload, new TypeReference<>() {});
    }

    private UUID walletId(String label) {
        return db.sql("SELECT id FROM wallets WHERE label = :l").param("l", label).query(UUID.class).single();
    }
}
