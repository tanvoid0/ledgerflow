package io.ledgerflow.notification.adapter.in.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class FundsHoldListener {

    private static final Logger log = LoggerFactory.getLogger(FundsHoldListener.class);

    /** A second copy of ledger's record. It will drift; step 08 turns it into a shared contract. */
    record FundsHeld(UUID holdId, UUID accountId, List<String> wallets, Instant expiresAt) {}

    @KafkaListener(topics = "wallet-hold-events")
    void onFundsHeld(FundsHeld event) {
        log.info("would email the customer about hold {} ({} wallets)",
                event.holdId(), event.wallets().size());
    }
}
