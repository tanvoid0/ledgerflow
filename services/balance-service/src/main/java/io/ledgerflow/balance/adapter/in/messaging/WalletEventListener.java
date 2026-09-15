package io.ledgerflow.balance.adapter.in.messaging;

import io.ledgerflow.balance.application.Balances;
import io.ledgerflow.balance.application.Balances.Delta;
import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.account.EntryPosted;
import io.ledgerflow.events.balance.BalanceSnapshot;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.events.ledger.HoldClosed;
import io.ledgerflow.starter.messaging.InvalidPayloadException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** Three topics, one projection: an entry moves balances, a hold moves held, and closing the hold moves it back. */
@Component
@RequiredArgsConstructor
@Slf4j
class WalletEventListener {

    private final Balances balances;
    private final JsonMapper json;
    private final KafkaTemplate<String, String> kafka;

    @KafkaListener(topics = {FundsHeld.TOPIC, HoldClosed.TOPIC, EntryPosted.TOPIC})
    void onEvent(EventEnvelope<JsonNode> event,
                 @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                 @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                 @Header(KafkaHeaders.OFFSET) long offset,
                 Acknowledgment ack) {
        if (event.payload() == null) throw new InvalidPayloadException("event " + event.eventId() + " has no payload");

        var deltas = switch (event.eventType()) {
            case FundsHeld.TYPE -> {
                var held = json.treeToValue(event.payload(), FundsHeld.class);
                yield held(held.wallets(), held.totalAmount(), +1);
            }
            case HoldClosed.TYPE -> {
                var closed = json.treeToValue(event.payload(), HoldClosed.class);
                yield held(closed.wallets(), closed.totalAmount(), -1);
            }
            case EntryPosted.TYPE -> json.treeToValue(event.payload(), EntryPosted.class).lines().stream()
                    .map(l -> new Delta(l.wallet(), l.amount().currency(), l.amount().minorUnits(), 0))
                    .toList();
            default -> throw new InvalidPayloadException("balance does not take " + event.eventType());
        };

        // the token ?after= waits on: the request that caused the write, or the event itself when nobody said
        var token = event.correlationId() != null ? event.correlationId() : event.eventId().toString();
        if (!balances.apply(event.eventId(), token, deltas)) log.info("event {} already applied, skipping", event.eventId());

        // published applied or not: a redelivery after a crash between apply and send still gets the snapshot out
        var source = new BalanceSnapshot.Source(topic, partition, offset);
        for (var wallet : deltas.stream().map(Delta::wallet).distinct().toList()) {
            balances.snapshot(wallet.accountId(), wallet.label()).ifPresent(s -> {
                var snapshot = new BalanceSnapshot(wallet.accountId(), wallet.label(), s.currency(), s.balanceMinor(), s.heldMinor(), s.version(), source);
                kafka.send(BalanceSnapshot.TOPIC, wallet.accountId() + ":" + wallet.label(), json.writeValueAsString(snapshot));
            });
        }
        ack.acknowledge();
    }

    private static List<Delta> held(List<WalletRef> wallets, Money amount, int sign) {
        return wallets.stream().map(w -> new Delta(w, amount.currency(), 0, sign * amount.minorUnits())).toList();
    }
}
