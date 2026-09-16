package io.ledgerflow.events.settlement;

import java.time.LocalDate;

public record MerchantSettled(String merchantId, LocalDate businessDate, String currency, long netMinor, long dayNetTotalMinor) {

    public static final String TYPE = "settlement.MerchantSettled";
    public static final String TOPIC = "ledgerflow.settlement.merchant.events.v1";
}
