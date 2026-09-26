package io.ledgerflow.account;

import io.ledgerflow.account.application.InsufficientFundsException;
import io.ledgerflow.account.application.PostTransfer;
import io.ledgerflow.events.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fifty virtual threads doing what perf/race.sh does, against real Postgres.
 * Tagged perf and excluded from the default run; -DexcludedGroups=none runs it.
 * Remove the WHERE clause from debitIfSufficient and this goes red.
 */
@Tag("perf")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@WithMockUser(roles = "ledger-write")
class TransferConcurrencyIT {

    @Autowired
    PostTransfer transfer;

    @Autowired
    JdbcClient db;

    @Test
    void fiftyRacingDebitsCannotOverdrawAWallet() throws Exception {
        UUID from = walletId("A-20"), to = walletId("A-19");       // A-20 holds exactly 100.00 from V4

        long started = System.nanoTime();
        // virtual threads don't inherit the test's security context on their own; this propagates it to each task
        try (var pool = new DelegatingSecurityContextExecutorService(Executors.newVirtualThreadPerTaskExecutor())) {
            var results = IntStream.range(0, 50).mapToObj(i -> pool.submit(() -> {
                try {
                    transfer.transfer("race-" + i, from, to, Money.gbp(8000), "race");
                    return true;
                } catch (InsufficientFundsException e) {
                    return false;
                }
            })).toList();
            long created = results.stream().filter(TransferConcurrencyIT::get).count();
            assertThat(created).as("only one 80.00 debit fits in 100.00").isEqualTo(1);
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf("50 racing transfers in %.2fs (%.0f/s, single node, in-process)%n", seconds, 50 / seconds);

        assertThat(db.sql("SELECT count(*) FROM wallets WHERE balance_minor < 0 AND label <> 'TREASURY'")
                .query(Long.class).single()).isZero();
        assertThat(db.sql("""
                SELECT count(*) FROM wallets w
                WHERE w.balance_minor <> COALESCE((SELECT SUM(amount_minor) FROM postings p
                                                   WHERE p.wallet_id = w.id AND p.currency = 'GBP'), 0)
                """).query(Long.class).single())
                .as("every balance equals the sum of its postings").isZero();
    }

    private UUID walletId(String label) {
        return db.sql("SELECT id FROM wallets WHERE label = :l").param("l", label).query(UUID.class).single();
    }

    private static boolean get(Future<Boolean> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
