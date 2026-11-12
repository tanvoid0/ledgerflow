package io.ledgerflow.settlement.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.settlement.SettleItem;
import io.ledgerflow.starter.messaging.Inbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticketing's production feed into the nightly batch: one captured order becomes one settlement_item,
 * NEW, waiting for its business date to be netted. Until this, settlement_item only ever had test
 * seeds. The inbox dedupes on eventId, so a redelivered capture inserts nothing twice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class SettleItemListener {

    private final Inbox inbox;
    private final JdbcClient db;
    private final JsonMapper json;

    @KafkaListener(topics = SettleItem.TOPIC)
    void onItem(EventEnvelope<JsonNode> event, Acknowledgment ack) {
        inbox.once(event, () -> {
            var item = json.treeToValue(event.payload(), SettleItem.class);
            db.sql("""
                    INSERT INTO settlement_item (payment_id, merchant_id, amount_minor, currency, business_date, status)
                    VALUES (:paymentId, :merchantId, :amount, :currency, :businessDate,
                            -- the revoke can land first (different topic): then the item is born already out of the net
                            CASE WHEN EXISTS (SELECT 1 FROM captures WHERE payment_id = :paymentId AND status = 'REVOKED')
                                 THEN 'REVOKED' ELSE 'NEW' END)
                    """)
                    .param("paymentId", item.paymentId())
                    .param("merchantId", item.merchantId())
                    .param("amount", item.amountMinor())
                    .param("currency", item.currency())
                    .param("businessDate", item.businessDate())
                    .update();
            log.info("settlement_item for payment {} merchant {} on {}: {} {}", item.paymentId(), item.merchantId(),
                    item.businessDate(), item.amountMinor(), item.currency());
        });
        ack.acknowledge();
    }
}
