package io.ledgerflow.ledger.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.ledger.CaptureHolds;
import io.ledgerflow.events.ledger.HoldClosed;
import io.ledgerflow.events.ledger.HoldRejected;
import io.ledgerflow.events.ledger.ReleaseWallets;
import io.ledgerflow.events.ledger.ReserveWallets;
import io.ledgerflow.ledger.application.FundsHoldRepository;
import io.ledgerflow.ledger.application.PlaceHold;
import io.ledgerflow.ledger.domain.model.FundsHold;
import io.ledgerflow.ledger.domain.model.UnknownWalletException;
import io.ledgerflow.starter.messaging.Inbox;
import io.ledgerflow.starter.messaging.InvalidPayloadException;
import io.ledgerflow.starter.messaging.OutboxAppender;
import io.ledgerflow.starter.web.RequestIdFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/** What the payment saga asks of ledger. Three commands share the topic so one reference's commands stay in order. */
@Component
@RequiredArgsConstructor
@Slf4j
class HoldCommandListener {

    private final Inbox inbox;
    private final PlaceHold placeHold;
    private final FundsHoldRepository holds;
    private final OutboxAppender outbox;
    private final JsonMapper json;

    @KafkaListener(topics = ReserveWallets.TOPIC)
    void onCommand(EventEnvelope<JsonNode> command, Acknowledgment ack) {
        inbox.once(command, () -> {
            switch (command.eventType()) {
                case ReserveWallets.TYPE -> reserve(command, json.treeToValue(command.payload(), ReserveWallets.class));
                case ReleaseWallets.TYPE -> close(command, json.treeToValue(command.payload(), ReleaseWallets.class).reference(), FundsHold.Status.RELEASED);
                case CaptureHolds.TYPE -> close(command, json.treeToValue(command.payload(), CaptureHolds.class).reference(), FundsHold.Status.CAPTURED);
                default -> throw new InvalidPayloadException("ledger does not take " + command.eventType());
            }
        });
        ack.acknowledge();
    }

    /**
     * An unknown wallet is an answer, not an error: retrying would not make the wallet exist, and a dead
     * letter would leave the saga waiting for its deadline. The rejection goes out through the outbox
     * like any other event, in the same transaction as the inbox mark.
     */
    private void reserve(EventEnvelope<?> command, ReserveWallets c) {
        // the account lookup inside PlaceHold runs within the inbox transaction here; its timeouts bound the wait
        MDC.put(RequestIdFilter.MDC_KEY, command.correlationId());   // so the FundsHeld events carry the payment's request id
        try {
            placeHold.place(c.accountId(), c.wallets(), c.amount(), c.reference());
        } catch (UnknownWalletException e) {
            log.info("rejecting reservation {}: {}", c.reference(), e.getMessage());
            outbox.append(HoldRejected.TOPIC, EventEnvelope.inReplyTo(command, HoldRejected.TYPE, c.reference(), 1,
                    new HoldRejected(c.reference(), e.getMessage())));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    /** One HoldClosed per hold, keyed like its FundsHeld, version 2: the second and last thing that happens to a hold. */
    private void close(EventEnvelope<?> command, UUID reference, FundsHold.Status to) {
        var closed = holds.closeAll(reference, to);
        for (var hold : closed) {
            var payload = new HoldClosed(hold.id(), List.of(new WalletRef(hold.accountId(), hold.walletCode())),
                    hold.amount(), hold.reference(), HoldClosed.Outcome.valueOf(to.name()));
            outbox.append(HoldClosed.TOPIC, EventEnvelope.inReplyTo(command, HoldClosed.TYPE, hold.id(), 2, payload));
        }
        log.info("{} hold(s) for {} now {}", closed.size(), reference, to);
    }
}
