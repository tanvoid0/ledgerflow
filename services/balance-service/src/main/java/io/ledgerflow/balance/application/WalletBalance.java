package io.ledgerflow.balance.application;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What the read model exists to answer. Available is the join nobody else can make: account's balance less ledger's open holds. */
public record WalletBalance(String label, String currency, long balanceMinor, long heldMinor) {

    @JsonProperty
    public long availableMinor() {
        return balanceMinor - heldMinor;
    }
}
