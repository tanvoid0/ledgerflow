package io.ledgerflow.payment.application;

import io.ledgerflow.events.HeldWallet;
import io.ledgerflow.events.Money;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The whole workflow, readable top to bottom: state + reply in, next state + commands out.
 * Pure on purpose: no IO, no Spring, so every path is a unit test that runs in a millisecond.
 * A reply the current state has no use for is ignored, not rejected: that is the late reply,
 * the one that arrives after a timeout already moved the payment on.
 */
public final class PaymentSaga {

    public record Command(String topic, String type, Record payload) {}

    public record Decision(PaymentState next, List<Command> commands) {}

    public static Decision start(UUID accountId, List<String> wallets, Money amount) {
        var paymentId = UUID.randomUUID();
        return new Decision(new Requested(paymentId, accountId, wallets, amount, List.of()),
                List.of(new Command(ReserveWallets.TOPIC, ReserveWallets.TYPE, new ReserveWallets(paymentId, accountId, wallets, amount))));
    }

    public static Decision on(PaymentState state, Record reply) {
        return switch (state) {

            case Requested r -> switch (reply) {
                case FundsHeld e -> held(r, e);
                case HoldRejected _ -> fail(r, FailureReason.WALLETS_UNAVAILABLE, Step.RESERVE, List.of());
                case StepTimedOut _ -> fail(r, FailureReason.TIMED_OUT, Step.RESERVE, List.of(release(r.paymentId())));
                default -> ignore(r);
            };

            case AuthorizationPending p -> switch (reply) {
                case PaymentAuthorized e -> new Decision(
                        new CapturePending(p.paymentId(), p.accountId(), p.held(), p.amount(), e.authorizationId()),
                        List.of(new Command(IssueCaptures.TOPIC, IssueCaptures.TYPE,
                                new IssueCaptures(p.paymentId(), p.accountId(), p.held(), p.amount()))));
                case PaymentDeclined _ -> fail(p, FailureReason.PAYMENT_DECLINED, Step.AUTHORIZE, List.of(release(p.paymentId())));
                // the issuer may have said yes after we stopped listening: refund whatever it did, then release
                case StepTimedOut _ -> fail(p, FailureReason.TIMED_OUT, Step.AUTHORIZE, List.of(refund(p.paymentId()), release(p.paymentId())));
                default -> ignore(p);
            };

            case CapturePending c -> switch (reply) {
                case CapturesIssued e -> new Decision(new Captured(c.paymentId(), e.captureIds()),
                        List.of(new Command(CaptureHolds.TOPIC, CaptureHolds.TYPE, new CaptureHolds(c.paymentId()))));
                case IssueFailed _ -> fail(c, FailureReason.ISSUE_FAILED, Step.ISSUE, undoEverything(c.paymentId()));
                case StepTimedOut _ -> fail(c, FailureReason.TIMED_OUT, Step.ISSUE, undoEverything(c.paymentId()));
                default -> ignore(c);
            };

            // terminal: whatever arrives now is late, and the compensations already sent cover it
            case Captured c -> ignore(c);
            case Failed f -> ignore(f);
        };
    }

    /** Ledger answers once per hold. Stay in Requested until every wallet has one; only then ask the issuer. */
    private static Decision held(Requested r, FundsHeld e) {
        var held = new ArrayList<>(r.held());
        e.wallets().stream()
                .filter(w -> held.stream().noneMatch(h -> h.wallet().equals(w.label())))
                .forEach(w -> held.add(new HeldWallet(e.holdId(), w.label())));
        var next = new Requested(r.paymentId(), r.accountId(), r.wallets(), r.amount(), List.copyOf(held));
        if (!next.allHeld()) return new Decision(next, List.of());
        return new Decision(new AuthorizationPending(r.paymentId(), r.accountId(), next.held(), r.amount()),
                List.of(new Command(AuthorizePayment.TOPIC, AuthorizePayment.TYPE, new AuthorizePayment(r.paymentId(), r.amount()))));
    }

    /** Compensate in reverse order of what may have happened: captures, then the authorization, then the holds. */
    private static List<Command> undoEverything(UUID paymentId) {
        return List.of(new Command(RevokeCaptures.TOPIC, RevokeCaptures.TYPE, new RevokeCaptures(paymentId)),
                refund(paymentId), release(paymentId));
    }

    private static Command release(UUID paymentId) {
        return new Command(ReleaseWallets.TOPIC, ReleaseWallets.TYPE, new ReleaseWallets(paymentId));
    }

    private static Command refund(UUID paymentId) {
        return new Command(RefundPayment.TOPIC, RefundPayment.TYPE, new RefundPayment(paymentId));
    }

    private static Decision fail(PaymentState s, FailureReason reason, Step at, List<Command> compensations) {
        return new Decision(new Failed(s.paymentId(), reason, at), compensations);
    }

    private static Decision ignore(PaymentState s) {
        return new Decision(s, List.of());
    }
}
