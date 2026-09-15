package io.ledgerflow.risk.application;

import io.ledgerflow.risk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** One account's counters, built one event at a time; a replay of an eventId must move none of them. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FeatureWindowIT {

    @Autowired
    FeatureWindow features;

    @Test
    void threeEventsBuildTheWindowFromEachOthersHistory() {
        var account = UUID.randomUUID();
        var now = System.currentTimeMillis();

        // 100 then 300: two different prior amounts, so the third event's z-score has a real sigma to land against
        var f1 = features.observe("e1", account, 100, "a", now).orElseThrow();
        assertThat(f1.countLastMinute()).isEqualTo(1);
        assertThat(f1.amountZScore()).isZero();       // no history yet
        assertThat(f1.newBeneficiary()).isTrue();

        var f2 = features.observe("e2", account, 300, "a", now + 1000).orElseThrow();
        assertThat(f2.countLastMinute()).isEqualTo(2);
        assertThat(f2.amountZScore()).isZero();       // one prior sample: still below the n>=2 floor
        assertThat(f2.newBeneficiary()).isFalse();

        var f3 = features.observe("e3", account, 5000, "b", now + 2000).orElseThrow();
        assertThat(f3.countLastMinute()).isEqualTo(3);
        assertThat(f3.amountZScore()).isGreaterThan(0);
        assertThat(f3.newBeneficiary()).isTrue();
    }

    @Test
    void replayingAnEventIdIsSkippedAndChangesNoCounter() {
        var account = UUID.randomUUID();
        var now = System.currentTimeMillis();
        features.observe("dup", account, 100, "a", now);

        assertThat(features.observe("dup", account, 999, "z", now + 1)).isEmpty();

        var next = features.observe("next", account, 100, "a", now + 2).orElseThrow();
        assertThat(next.countLastMinute()).isEqualTo(2);   // the duplicate never counted
    }
}
