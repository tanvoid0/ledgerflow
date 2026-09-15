package io.ledgerflow.risk.application;

import io.ledgerflow.events.Money;
import io.ledgerflow.events.payment.PaymentRequested;
import io.ledgerflow.risk.application.FeatureWindow.Features;
import io.ledgerflow.risk.domain.model.Decision.Allow;
import io.ledgerflow.risk.domain.model.Decision.Block;
import io.ledgerflow.risk.domain.model.Decision.Review;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The transition function is pure: no Spring, no database, every path a rule or a threshold can take. */
class DecisionTest {

    static final Rules RULES = new Rules(1_000_000, List.of("mule-1"));
    static final Features FEATURES = new Features(0, 0, false);

    @Test
    void aBlockedBeneficiaryWinsOverAScoreThatWouldOtherwiseAllow() {
        var p = payment(Money.gbp(100), "mule-1");
        assertThat(RULES.decide(p, FEATURES, 0.99, "lr-v1", 0.8)).isEqualTo(new Block("BLOCKED_BENEFICIARY"));
    }

    @Test
    void anAmountOverTheLimitBlocksRegardlessOfScore() {
        var p = payment(Money.gbp(1_000_001), "someone");
        assertThat(RULES.decide(p, FEATURES, 0.0, "lr-v1", 0.8)).isEqualTo(new Block("LIMIT"));
    }

    @Test
    void aScoreAtOrAboveTheThresholdIsReviewedWithTheScoreAndModelInTheReason() {
        var p = payment(Money.gbp(100), "someone");
        assertThat(RULES.decide(p, FEATURES, 0.8, "lr-v1", 0.8)).isEqualTo(new Review("score 0.800 model lr-v1"));
    }

    @Test
    void aScoreBelowTheThresholdIsAllowed() {
        var p = payment(Money.gbp(100), "someone");
        assertThat(RULES.decide(p, FEATURES, 0.79, "lr-v1", 0.8)).isEqualTo(new Allow());
    }

    private static PaymentRequested payment(Money amount, String beneficiary) {
        return new PaymentRequested(UUID.randomUUID(), UUID.randomUUID(), List.of("A-12"), amount, beneficiary);
    }
}
