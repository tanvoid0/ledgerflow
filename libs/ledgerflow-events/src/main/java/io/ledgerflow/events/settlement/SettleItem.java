package io.ledgerflow.events.settlement;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One captured order, ready to be netted for its organiser. Sent by ticketing when a payment
 * settles (an order reaches CONFIRMED): the first production writer of settlement_item, which until
 * now only tests seeded. merchantId is the event's organiser; businessDate is the day the batch
 * nets it under. Deduped on eventId through settlement's inbox, so a redelivery inserts nothing new.
 */
public record SettleItem(UUID paymentId, String merchantId, long amountMinor, String currency, LocalDate businessDate) {

    public static final String TYPE = "settlement.SettleItem";
    public static final String TOPIC = "ledgerflow.settlement.item.commands.v1";
}
