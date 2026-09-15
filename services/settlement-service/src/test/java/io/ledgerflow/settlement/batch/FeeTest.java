package io.ledgerflow.settlement.batch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 2.9% + 30, integer arithmetic: the floor doesn't round in the merchant's favor. */
class FeeTest {

    @Test
    void tenThousandMinorIsTwoNineFourZeroPlusThirty() {
        assertThat(SettlementJobConfig.fee(10_000)).isEqualTo(320);
    }

    @Test
    void oneMinorIsStillThirty() {
        assertThat(SettlementJobConfig.fee(1)).isEqualTo(30);
    }

    @Test
    void zeroIsStillThirty() {
        assertThat(SettlementJobConfig.fee(0)).isEqualTo(30);
    }
}
