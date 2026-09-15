package io.ledgerflow.settlement.batch;

import java.time.LocalDate;
import java.util.UUID;

/** One row of settlement_item: a capture waiting to be netted for its merchant. */
public record SettlementItem(long id, UUID paymentId, String merchantId, long amountMinor, String currency,
                              LocalDate businessDate) {}
