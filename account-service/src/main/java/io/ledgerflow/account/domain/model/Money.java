package io.ledgerflow.account.domain.model;

/**
 * Minor units only: 4500 means GBP 45.00.
 * Never a double. 0.1 + 0.2 is not 0.3 in binary floating point, and a ledger
 * that drifts by a penny is wrong.
 */
public record Money(long minorUnits, String currency) {

    public Money {
        if (currency == null || currency.length() != 3)
            throw new IllegalArgumentException("currency must be an ISO-4217 code");
    }

    public static Money gbp(long minorUnits) {
        return new Money(minorUnits, "GBP");
    }

    public Money negate() {
        return new Money(-minorUnits, currency);
    }
}
