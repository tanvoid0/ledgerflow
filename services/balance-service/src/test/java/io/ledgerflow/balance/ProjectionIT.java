package io.ledgerflow.balance;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.account.EntryPosted;
import io.ledgerflow.events.balance.BalanceSnapshot;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.events.ledger.HoldClosed;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.JacksonMapperUtils;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Events in, the available balance out: the join across two services that neither of them can answer. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@WithMockUser
class ProjectionIT {

    static {
        // await() polls on its own thread by default; @WithMockUser's SecurityContext is a ThreadLocal on
        // the test thread, so a token-authenticated request from the poller would otherwise come back 401.
        org.awaitility.Awaitility.pollInSameThread();
    }

    static final JsonMapper json = JacksonMapperUtils.enhancedJsonMapper();
    static final UUID ACCOUNT = UUID.randomUUID();
    static final WalletRef A12 = new WalletRef(ACCOUNT, "A-12");
    static final WalletRef TREASURY = new WalletRef(ACCOUNT, "TREASURY");

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired MockMvcTester mvc;
    @Autowired RedpandaContainer redpanda;   // not ${spring.kafka.bootstrap-servers}: that is the yml's localhost:9092, the host's broker

    @Test
    void anEntryAndAHoldBecomeAnAvailableBalance_andReplayingThemChangesNothing() {
        var entry = UUID.randomUUID();
        var hold = UUID.randomUUID();
        var opening = EventEnvelope.of(EntryPosted.TYPE, entry, 1, "req-1", "req-1", new EntryPosted(entry, "opening",
                List.of(new EntryPosted.Line(TREASURY, Money.gbp(-10000)), new EntryPosted.Line(A12, Money.gbp(10000)))));
        var held = EventEnvelope.of(FundsHeld.TYPE, hold, 1, "req-2", "req-2",
                new FundsHeld(hold, List.of(A12), Instant.now(), Money.gbp(4500), null));

        send(EntryPosted.TOPIC, opening);
        send(FundsHeld.TOPIC, held);

        // read-your-own-writes: the response waits for req-2 to land on A-12 (the await covers the group join)
        await().untilAsserted(() -> assertThat(mvc.get().uri("/api/v1/balances/{a}/A-12?after=req-2", ACCOUNT).exchange())
                .hasStatusOk().hasHeader("X-Projection", "caught-up"));
        var view = mvc.get().uri("/api/v1/balances/{a}/A-12", ACCOUNT).exchange();
        assertThat(view).bodyJson().extractingPath("$.balanceMinor").isEqualTo(10000);
        assertThat(view).bodyJson().extractingPath("$.heldMinor").isEqualTo(4500);
        assertThat(view).bodyJson().extractingPath("$.availableMinor").isEqualTo(5500);

        send(EntryPosted.TOPIC, opening);   // what a rewound group delivers, or a retried outbox row
        send(FundsHeld.TOPIC, held);
        var closed = EventEnvelope.inReplyTo(held, HoldClosed.TYPE, hold, 2,
                new HoldClosed(hold, List.of(A12), Money.gbp(4500), null, HoldClosed.Outcome.RELEASED));
        send(HoldClosed.TOPIC, closed);

        await().untilAsserted(() -> assertThat(mvc.get().uri("/api/v1/balances/{a}/A-12", ACCOUNT).exchange())
                .bodyJson().extractingPath("$.heldMinor").isEqualTo(0));
        var account = mvc.get().uri("/api/v1/balances/{a}", ACCOUNT).exchange();
        assertThat(account).bodyJson().extractingPath("$.wallets[?(@.label=='A-12')].availableMinor").isEqualTo(List.of(10000));
        assertThat(account).bodyJson().extractingPath("$.wallets[?(@.label=='TREASURY')].balanceMinor").isEqualTo(List.of(-10000));
    }

    @Test
    void aWriteTheProjectionHasNotSeenIsServedAndSaidSo() {
        var account = UUID.randomUUID();
        var entry = UUID.randomUUID();
        send(EntryPosted.TOPIC, EventEnvelope.of(EntryPosted.TYPE, entry, 1, "req-3", "req-3", new EntryPosted(entry, "opening",
                List.of(new EntryPosted.Line(new WalletRef(account, "TREASURY"), Money.gbp(-100)),
                        new EntryPosted.Line(new WalletRef(account, "A-1"), Money.gbp(100))))));
        await().untilAsserted(() -> assertThat(mvc.get().uri("/api/v1/balances/{a}/A-1?after=req-3", account).exchange())
                .hasStatusOk().hasHeader("X-Projection", "caught-up"));

        // two seconds pass, the request never shows up: the view is served anyway, and the header says it may be stale
        assertThat(mvc.get().uri("/api/v1/balances/{a}/A-1?after=req-never", account).exchange())
                .hasStatusOk().hasHeader("X-Projection", "lagging");
        assertThat(mvc.get().uri("/api/v1/balances/{a}/ZZ-99", account).exchange()).hasStatus(404);
    }

    @Test
    void everyApplyPublishesASnapshotAndTheHighestVersionMatchesTheReadModel() {
        var account = UUID.randomUUID();
        var wallet = new WalletRef(account, "A-1");
        var entry = UUID.randomUUID();
        var hold = UUID.randomUUID();

        send(EntryPosted.TOPIC, EventEnvelope.of(EntryPosted.TYPE, entry, 1, "req-4", "req-4", new EntryPosted(entry, "opening",
                List.of(new EntryPosted.Line(new WalletRef(account, "TREASURY"), Money.gbp(-500)),
                        new EntryPosted.Line(wallet, Money.gbp(500))))));
        send(FundsHeld.TOPIC, EventEnvelope.of(FundsHeld.TYPE, hold, 1, "req-5", "req-5",
                new FundsHeld(hold, List.of(wallet), Instant.now(), Money.gbp(200), null)));

        await().untilAsserted(() -> assertThat(mvc.get().uri("/api/v1/balances/{a}/A-1?after=req-5", account).exchange())
                .hasStatusOk().hasHeader("X-Projection", "caught-up"));
        var view = mvc.get().uri("/api/v1/balances/{a}/A-1", account).exchange();

        var snapshot = highestSnapshot(account, "A-1");
        assertThat(snapshot).isNotNull();
        assertThat(view).bodyJson().extractingPath("$.balanceMinor").isEqualTo((int) snapshot.balanceMinor());
        assertThat(view).bodyJson().extractingPath("$.heldMinor").isEqualTo((int) snapshot.heldMinor());
    }

    private void send(String topic, EventEnvelope<?> envelope) {
        kafka.send(topic, envelope.aggregateId().toString(), json.writeValueAsString(envelope));
    }

    /** Reads the snapshot topic from the start and keeps the highest version seen for one wallet. */
    private BalanceSnapshot highestSnapshot(UUID accountId, String label) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            // assign, not subscribe: no group to join, so no initial-rebalance delay eating the deadline
            var partitions = consumer.partitionsFor(BalanceSnapshot.TOPIC).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            BalanceSnapshot highest = null;
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(300));
                for (var record : records) {
                    var snap = json.readValue(record.value(), BalanceSnapshot.class);
                    if (snap.accountId().equals(accountId) && snap.label().equals(label)
                            && (highest == null || snap.version() > highest.version())) highest = snap;
                }
                if (records.isEmpty() && highest != null) break;   // caught up: no more records after finding one
            }
            return highest;
        }
    }
}
