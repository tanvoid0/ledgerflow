package io.ledgerflow.payment.domain.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ledgerflow.events.HeldWallet;
import io.ledgerflow.events.Money;

import java.util.List;
import java.util.UUID;

/**
 * Every state a payment can be in, each carrying exactly what that state needs. Sealed, so the
 * transition function's switch must name them all: add a state and the build breaks until it does.
 * Serialised as-is, with the record's name under "state": that is both the row and the API response.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME, property = "state")
public sealed interface PaymentState {

    UUID paymentId();

    /** The step whose reply this state is waiting for; null once nothing more can happen. */
    Step step();

    enum Step { RESERVE, AUTHORIZE, ISSUE }

    enum FailureReason { WALLETS_UNAVAILABLE, PAYMENT_DECLINED, ISSUE_FAILED, TIMED_OUT }

    /** Waiting for ledger. One hold per wallet comes back as one FundsHeld each; held grows until every wallet is in it. */
    record Requested(UUID paymentId, UUID accountId, List<String> wallets, Money amount, List<HeldWallet> held)
            implements PaymentState {
        public Step step() { return Step.RESERVE; }
        public boolean allHeld() { return held.stream().map(HeldWallet::wallet).toList().containsAll(wallets); }
    }

    record AuthorizationPending(UUID paymentId, UUID accountId, List<HeldWallet> held, Money amount)
            implements PaymentState {
        public Step step() { return Step.AUTHORIZE; }
    }

    record CapturePending(UUID paymentId, UUID accountId, List<HeldWallet> held, Money amount, UUID authorizationId)
            implements PaymentState {
        public Step step() { return Step.ISSUE; }
    }

    record Captured(UUID paymentId, List<UUID> captures) implements PaymentState {
        public Step step() { return null; }
    }

    record Failed(UUID paymentId, FailureReason reason, Step failedAt) implements PaymentState {
        public Step step() { return null; }
    }
}
