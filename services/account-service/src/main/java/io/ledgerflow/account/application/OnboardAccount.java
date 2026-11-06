package io.ledgerflow.account.application;

import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.account.EntryPosted;
import io.ledgerflow.starter.messaging.OutboxAppender;
import io.ledgerflow.starter.web.RequestIdFilter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Gives a self-registered customer what the V9 seed gives alice and bob: an account, a MAIN wallet, and
 * an opening balance paid out of treasury. Without it a fresh Keycloak user has no account, and the first
 * ticket's fund hold 404s. Idempotent: calling twice is a no-op, so checkout can call it every time.
 */
@Service
@RequiredArgsConstructor
public class OnboardAccount {

    /** Where money enters the system; runs negative by construction (JdbcLedgerRepository.debitIfSufficient). */
    private static final UUID TREASURY = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final Money OPENING_BALANCE = Money.gbp(50_000);   // £500, same as the seeded shoppers

    private final LedgerRepository ledger;
    private final OutboxAppender outbox;

    @Transactional
    public void onboard(UUID accountId, String name) {
        ledger.createAccountIfAbsent(accountId, name);
        var mainWallet = ledger.walletId(accountId, "MAIN");

        // the opening grant is a balanced entry out of treasury, keyed by the account: run once, replay is a no-op
        var key = "onboard:" + accountId;
        if (ledger.findByIdempotencyKey(key).isPresent()) return;

        var treasury = ledger.debitIfSufficient(TREASURY, OPENING_BALANCE).orElseThrow();
        var credited = ledger.credit(mainWallet, OPENING_BALANCE);
        var entry = JournalEntry.transfer(key, TREASURY, mainWallet, OPENING_BALANCE, "opening balance");
        ledger.append(entry);

        var requestId = MDC.get(RequestIdFilter.MDC_KEY);
        var posted = new EntryPosted(entry.id(), "opening balance",
                List.of(new EntryPosted.Line(treasury, OPENING_BALANCE.negate()), new EntryPosted.Line(credited, OPENING_BALANCE)));
        outbox.append(EntryPosted.TOPIC, EventEnvelope.of(EntryPosted.TYPE, entry.id(), 1, requestId, requestId, posted));
    }
}
