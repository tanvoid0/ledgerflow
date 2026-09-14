package io.ledgerflow.settlement.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.HeldWallet;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.settlement.CapturesIssued;
import io.ledgerflow.events.settlement.IssueCaptures;
import io.ledgerflow.events.settlement.IssueFailed;
import io.ledgerflow.events.settlement.RevokeCaptures;
import io.ledgerflow.settlement.adapter.out.account.AccountClient;
import io.ledgerflow.settlement.adapter.out.account.AccountClient.TransferRequest;
import io.ledgerflow.settlement.config.SettlementProperties;
import io.ledgerflow.starter.messaging.Inbox;
import io.ledgerflow.starter.messaging.InvalidPayloadException;
import io.ledgerflow.starter.messaging.OutboxAppender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Capture = the money a hold reserved actually moves, wallet to settlement wallet, through account-service.
 * The Idempotency-Key is derived from the hold, so a redelivered command re-asks account for the same
 * transfer and gets the same entry back: the HTTP call is safe to repeat, and so is this handler.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class CaptureCommandListener {

    private final Inbox inbox;
    private final JdbcClient db;
    private final OutboxAppender outbox;
    private final AccountClient account;
    private final SettlementProperties props;
    private final JsonMapper json;

    @KafkaListener(topics = IssueCaptures.TOPIC)
    void onCommand(EventEnvelope<JsonNode> command, Acknowledgment ack) {
        inbox.once(command, () -> {
            switch (command.eventType()) {
                case IssueCaptures.TYPE -> issue(command, json.treeToValue(command.payload(), IssueCaptures.class));
                case RevokeCaptures.TYPE -> revoke(json.treeToValue(command.payload(), RevokeCaptures.class).paymentId());
                default -> throw new InvalidPayloadException("settlement does not take " + command.eventType());
            }
        });
        ack.acknowledge();
    }

    /**
     * One transfer per hold, in order. A wallet that cannot cover it stops the loop: what did move is
     * recorded as captured (the saga will send RevokeCaptures), and the reply says why it stopped.
     */
    private void issue(EventEnvelope<?> command, IssueCaptures c) {
        var wallets = account.account(c.accountId());
        var captured = new ArrayList<UUID>();
        for (HeldWallet hold : c.holds()) {
            var from = wallets.walletId(hold.wallet());
            try {
                var entry = account.transfer("capture-" + hold.holdId(), new TransferRequest(
                        from, props.settlementWalletId(), c.amount().minorUnits(), c.amount().currency(),
                        "capture of hold " + hold.holdId() + " for payment " + c.paymentId()));
                record(entry.entryId(), c.paymentId(), hold.holdId(), from, c.amount());
                captured.add(entry.entryId());
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != 422) throw e;   // 422 is "insufficient funds"; anything else is our bug
                log.info("capture for payment {} stopped at wallet {}: {}", c.paymentId(), hold.wallet(), e.getMessage());
                outbox.append(IssueFailed.TOPIC, EventEnvelope.inReplyTo(command, IssueFailed.TYPE, c.paymentId(), 1,
                        new IssueFailed(c.paymentId(), "wallet " + hold.wallet() + " could not cover " + c.amount().minorUnits())));
                return;
            }
        }
        log.info("captured {} hold(s) for payment {}", captured.size(), c.paymentId());
        outbox.append(CapturesIssued.TOPIC, EventEnvelope.inReplyTo(command, CapturesIssued.TYPE, c.paymentId(), 1,
                new CapturesIssued(c.paymentId(), captured)));
    }

    /** Every capture goes back the way it came, keyed by the capture so a repeat is the same reversal. */
    private void revoke(UUID paymentId) {
        var open = db.sql("SELECT id, wallet_id, amount_minor, currency FROM captures WHERE payment_id = :paymentId AND status = 'CAPTURED'")
                .param("paymentId", paymentId).query(Capture.class).list();
        for (var capture : open) {
            account.transfer("revoke-" + capture.id(), new TransferRequest(
                    props.settlementWalletId(), capture.walletId(), capture.amountMinor(), capture.currency(),
                    "reversal of capture " + capture.id()));
            db.sql("UPDATE captures SET status = 'REVOKED' WHERE id = :id").param("id", capture.id()).update();
        }
        log.info("revoke for payment {}: {} capture(s) reversed", paymentId, open.size());
    }

    private void record(UUID entryId, UUID paymentId, UUID holdId, UUID walletId, Money amount) {
        db.sql("""
                INSERT INTO captures (id, payment_id, hold_id, wallet_id, amount_minor, currency, status)
                VALUES (:id, :paymentId, :holdId, :walletId, :amount, :currency, 'CAPTURED')
                ON CONFLICT (hold_id) DO NOTHING
                """)
                .param("id", entryId).param("paymentId", paymentId).param("holdId", holdId).param("walletId", walletId)
                .param("amount", amount.minorUnits()).param("currency", amount.currency())
                .update();
    }

    record Capture(UUID id, UUID walletId, long amountMinor, String currency) {}
}
