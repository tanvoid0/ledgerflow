package io.ledgerflow.settlement.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.settlement.MerchantSettled;
import io.ledgerflow.settlement.adapter.out.account.AccountClient;
import io.ledgerflow.settlement.adapter.out.account.AccountClient.TransferRequest;
import io.ledgerflow.settlement.config.SettlementProperties;
import io.ledgerflow.starter.messaging.Inbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The other half of the money story: when the nightly batch nets a merchant (MerchantSettled), move
 * its net out of TREASURY (the settlement wallet, where captured ticket money sits) into the
 * organiser's MAIN wallet. The organiser's account id is derived from its name exactly as the V10
 * seed wrote it, so no name->account table is needed. The Idempotency-Key is the merchant and the
 * day: a batch rerun re-asks account for the same transfer and gets the same entry back, so paying
 * out twice is impossible, redelivery included.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class MerchantPayoutListener {

    private final Inbox inbox;
    private final AccountClient account;
    private final SettlementProperties props;
    private final JsonMapper json;

    @KafkaListener(topics = MerchantSettled.TOPIC)
    void onSettled(EventEnvelope<JsonNode> event, Acknowledgment ack) {
        inbox.once(event, () -> {
            var settled = json.treeToValue(event.payload(), MerchantSettled.class);
            if (settled.netMinor() <= 0) {
                log.info("payout for {} on {}: net {} not positive, nothing to pay", settled.merchantId(), settled.businessDate(), settled.netMinor());
                return;
            }
            var organiserId = UUID.nameUUIDFromBytes(("organiser:" + settled.merchantId()).getBytes(StandardCharsets.UTF_8));
            UUID payoutWallet;
            try {
                payoutWallet = account.account(organiserId).walletId("MAIN");
            } catch (HttpClientErrorException.NotFound e) {
                // An organiser with no payout account (not seeded). Skip, don't poison the listener:
                // onboarding one at event-create is the named upgrade.
                log.warn("payout for {} on {}: no payout account {} (unseeded organiser); skipping", settled.merchantId(), settled.businessDate(), organiserId);
                return;
            }
            account.transfer("payout-" + settled.merchantId() + "-" + settled.businessDate(), new TransferRequest(
                    props.settlementWalletId(), payoutWallet, settled.netMinor(), settled.currency(),
                    "payout to " + settled.merchantId() + " for " + settled.businessDate()));
            log.info("paid {} {} to {} for {}", settled.netMinor(), settled.currency(), settled.merchantId(), settled.businessDate());
        });
        ack.acknowledge();
    }
}
