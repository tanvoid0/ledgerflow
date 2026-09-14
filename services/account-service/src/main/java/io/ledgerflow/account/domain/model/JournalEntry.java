package io.ledgerflow.account.domain.model;

import io.ledgerflow.events.Money;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An atomic movement of money: two or more postings that sum to zero per currency.
 * The compact constructor is the whole point - an unbalanced entry cannot exist.
 */
public record JournalEntry(UUID id, String idempotencyKey, String description, List<Posting> postings) {

    public JournalEntry {
        if (postings == null || postings.size() < 2)
            throw new UnbalancedEntryException("an entry needs at least two postings");

        var perCurrency = postings.stream().collect(Collectors.groupingBy(
                p -> p.amount().currency(),
                Collectors.summingLong(p -> p.amount().minorUnits())));
        perCurrency.forEach((currency, sum) -> {
            if (sum != 0) throw new UnbalancedEntryException(currency + " postings sum to " + sum + ", not 0");
        });

        postings = List.copyOf(postings);
    }

    /** The common case: move an amount from one wallet to another. */
    public static JournalEntry transfer(String idempotencyKey, UUID from, UUID to, Money amount, String description) {
        return new JournalEntry(UUID.randomUUID(), idempotencyKey, description,
                List.of(new Posting(from, amount.negate()), new Posting(to, amount)));
    }
}
