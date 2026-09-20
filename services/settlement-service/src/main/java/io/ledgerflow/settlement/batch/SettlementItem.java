package io.ledgerflow.settlement.batch;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/** One row of settlement_item: a capture waiting to be netted for its merchant. Serializable: remote chunking JDK-serialises the whole chunk, this item included. */
public record SettlementItem(long id, UUID paymentId, String merchantId, long amountMinor, String currency,
                              LocalDate businessDate) implements Serializable {}
