package io.ledgerflow.payment.application;

import io.ledgerflow.events.HeldWallet;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.issuer.AuthorizePayment;
import io.ledgerflow.events.issuer.PaymentAuthorized;
import io.ledgerflow.events.issuer.PaymentDeclined;
import io.ledgerflow.events.issuer.RefundPayment;
import io.ledgerflow.events.ledger.CaptureHolds;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.events.ledger.HoldRejected;
import io.ledgerflow.events.ledger.ReleaseWallets;
import io.ledgerflow.events.ledger.ReserveWallets;
import io.ledgerflow.events.settlement.CapturesIssued;
import io.ledgerflow.events.settlement.IssueCaptures;
import io.ledgerflow.events.settlement.IssueFailed;
import io.ledgerflow.events.settlement.RevokeCaptures;
import io.ledgerflow.payment.domain.model.PaymentState;
import io.ledgerflow.payment.domain.model.PaymentState.AuthorizationPending;
import io.ledgerflow.payment.domain.model.PaymentState.CapturePending;
import io.ledgerflow.payment.domain.model.PaymentState.Captured;
import io.ledgerflow.payment.domain.model.PaymentState.Failed;
import io.ledgerflow.payment.domain.model.PaymentState.FailureReason;
import io.ledgerflow.payment.domain.model.PaymentState.Requested;
import io.ledgerflow.payment.domain.model.PaymentState.Step;
import io.ledgerflow.payment.domain.model.StepTimedOut;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Every path through the workflow, no Spring, no database: the transition function is a pure function. */
class PaymentSagaTest {

    static final UUID ACCOUNT = UUID.randomUUID();
    static final Money AMOUNT = Money.gbp(4500);

    @Test
    void happyPath_reserveAuthorizeIssueCaptured() {
        var started = PaymentSaga.start(ACCOUNT, List.of("A-12"), AMOUNT);
        var id = started.next().paymentId();
        assertThat(started.next()).isInstanceOf(Requested.class);
        assertThat(payloads(started)).containsExactly(new ReserveWallets(id, ACCOUNT, List.of("A-12"), AMOUNT));

        var hold = UUID.randomUUID();
        var held = PaymentSaga.on(started.next(), fundsHeld(hold, "A-12", id));
        assertThat(held.next()).isEqualTo(new AuthorizationPending(id, ACCOUNT, List.of(new HeldWallet(hold, "A-12")), AMOUNT));
        assertThat(payloads(held)).containsExactly(new AuthorizePayment(id, AMOUNT));

        var auth = UUID.randomUUID();
        var authorized = PaymentSaga.on(held.next(), new PaymentAuthorized(id, auth, AMOUNT));
        assertThat(authorized.next()).isEqualTo(new CapturePending(id, ACCOUNT, List.of(new HeldWallet(hold, "A-12")), AMOUNT, auth));
        assertThat(payloads(authorized)).containsExactly(new IssueCaptures(id, ACCOUNT, List.of(new HeldWallet(hold, "A-12")), AMOUNT));

        var capture = UUID.randomUUID();
        var captured = PaymentSaga.on(authorized.next(), new CapturesIssued(id, List.of(capture)));
        assertThat(captured.next()).isEqualTo(new Captured(id, List.of(capture)));
        assertThat(payloads(captured)).containsExactly(new CaptureHolds(id));
    }

    @Test
    void twoWallets_waitsForBothHoldsBeforeAskingTheIssuer() {
        var started = PaymentSaga.start(ACCOUNT, List.of("A-12", "A-13"), AMOUNT);
        var id = started.next().paymentId();

        var first = PaymentSaga.on(started.next(), fundsHeld(UUID.randomUUID(), "A-12", id));
        assertThat(first.next()).isInstanceOf(Requested.class);
        assertThat(first.commands()).isEmpty();

        var again = PaymentSaga.on(first.next(), fundsHeld(UUID.randomUUID(), "A-12", id));   // redelivered
        assertThat(again.next()).isEqualTo(first.next());

        var second = PaymentSaga.on(first.next(), fundsHeld(UUID.randomUUID(), "A-13", id));
        assertThat(second.next()).isInstanceOf(AuthorizationPending.class);
        assertThat(payloads(second)).containsExactly(new AuthorizePayment(id, AMOUNT));
    }

    @Test
    void paymentDeclined_failsAndReleasesTheWallets() {
        var pending = authorizationPending();
        var d = PaymentSaga.on(pending, new PaymentDeclined(pending.paymentId(), "no"));
        assertThat(d.next()).isEqualTo(new Failed(pending.paymentId(), FailureReason.PAYMENT_DECLINED, Step.AUTHORIZE));
        assertThat(payloads(d)).containsExactly(new ReleaseWallets(pending.paymentId()));
    }

    @Test
    void issueFailed_undoesEverythingInReverseOrder() {
        var pending = new CapturePending(UUID.randomUUID(), ACCOUNT, List.of(new HeldWallet(UUID.randomUUID(), "A-12")), AMOUNT, UUID.randomUUID());
        var d = PaymentSaga.on(pending, new IssueFailed(pending.paymentId(), "insufficient"));
        assertThat(d.next()).isEqualTo(new Failed(pending.paymentId(), FailureReason.ISSUE_FAILED, Step.ISSUE));
        assertThat(payloads(d)).containsExactly(new RevokeCaptures(pending.paymentId()),
                new RefundPayment(pending.paymentId()), new ReleaseWallets(pending.paymentId()));
    }

    @Test
    void issuerNeverAnswers_timesOutRefundsWhateverItDidAndAbsorbsTheLateReply() {
        var pending = authorizationPending();
        var timedOut = PaymentSaga.on(pending, new StepTimedOut(Step.AUTHORIZE));
        assertThat(timedOut.next()).isEqualTo(new Failed(pending.paymentId(), FailureReason.TIMED_OUT, Step.AUTHORIZE));
        assertThat(payloads(timedOut)).containsExactly(new RefundPayment(pending.paymentId()), new ReleaseWallets(pending.paymentId()));

        // the issuer wakes up and says yes: too late, and the refund already sent covers it
        var late = PaymentSaga.on(timedOut.next(), new PaymentAuthorized(pending.paymentId(), UUID.randomUUID(), AMOUNT));
        assertThat(late.next()).isEqualTo(timedOut.next());
        assertThat(late.commands()).isEmpty();
    }

    @Test
    void walletsUnavailable_failsWithNothingToUndo() {
        var started = PaymentSaga.start(ACCOUNT, List.of("ZZ-99"), AMOUNT);
        var d = PaymentSaga.on(started.next(), new HoldRejected(started.next().paymentId(), "no such wallet"));
        assertThat(d.next()).isEqualTo(new Failed(started.next().paymentId(), FailureReason.WALLETS_UNAVAILABLE, Step.RESERVE));
        assertThat(d.commands()).isEmpty();
    }

    @Test
    void ledgerNeverAnswers_releasesInCaseTheHoldLandsLater() {
        var started = PaymentSaga.start(ACCOUNT, List.of("A-12"), AMOUNT);
        var d = PaymentSaga.on(started.next(), new StepTimedOut(Step.RESERVE));
        assertThat(d.next()).isInstanceOf(Failed.class);
        assertThat(payloads(d)).containsExactly(new ReleaseWallets(started.next().paymentId()));
    }

    private static AuthorizationPending authorizationPending() {
        return new AuthorizationPending(UUID.randomUUID(), ACCOUNT, List.of(new HeldWallet(UUID.randomUUID(), "A-12")), AMOUNT);
    }

    private static FundsHeld fundsHeld(UUID holdId, String wallet, UUID paymentId) {
        return new FundsHeld(holdId, List.of(new WalletRef(ACCOUNT, wallet)), Instant.now(), AMOUNT, paymentId);
    }

    private static List<Record> payloads(PaymentSaga.Decision d) {
        return d.commands().stream().map(PaymentSaga.Command::payload).toList();
    }
}
