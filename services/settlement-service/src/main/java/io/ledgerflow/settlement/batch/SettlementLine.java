package io.ledgerflow.settlement.batch;

import java.time.LocalDate;

/** One settlement_item, fee taken out: what the merchant is owed for that one payment. */
record SettlementLine(long itemId, LocalDate businessDate, String merchantId, String currency,
                       long grossMinor, long feeMinor, long netMinor) {}
