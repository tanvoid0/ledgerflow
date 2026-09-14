package io.ledgerflow.account.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void transferBalances() {
        var entry = JournalEntry.transfer("k1", a, b, Money.gbp(4500), "test");

        assertThat(entry.postings()).hasSize(2);
        assertThat(entry.postings().stream().mapToLong(p -> p.amount().minorUnits()).sum()).isZero();
    }

    @Test
    void refusesAnUnbalancedEntry() {
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), "k2", "bad",
                List.of(new Posting(a, Money.gbp(-100)), new Posting(b, Money.gbp(99)))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("GBP");
    }

    @Test
    void refusesASinglePosting() {
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), "k3", "bad",
                List.of(new Posting(a, Money.gbp(-100)))))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void balancesPerCurrencyNotAcrossThem() {
        // GBP -100 and EUR +100 do not cancel out; each currency must balance on its own
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), "k4", "bad",
                List.of(new Posting(a, Money.gbp(-100)), new Posting(b, new Money(100, "EUR")))))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void moneyRefusesANonIsoCurrency() {
        assertThatThrownBy(() -> new Money(1, "pounds")).isInstanceOf(IllegalArgumentException.class);
    }
}
