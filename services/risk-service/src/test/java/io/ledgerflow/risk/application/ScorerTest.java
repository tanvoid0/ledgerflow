package io.ledgerflow.risk.application;

import io.ledgerflow.risk.application.FeatureWindow.Features;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The model itself, no Spring: a quiet account scores low, a loud one against a new beneficiary scores high. */
class ScorerTest {

    Scorer scorer = new Scorer(new SimpleMeterRegistry());

    @Test
    void aQuietAccountScoresLow() {
        assertThat(scorer.score(new Features(0, 0, false))).isLessThan(0.2);
    }

    @Test
    void manyPaymentsAFarAmountAndANewBeneficiaryScoreHigh() {
        assertThat(scorer.score(new Features(12, 4, true))).isGreaterThan(0.8);
    }

    @Test
    void theModelVersionComesFromItsMetadata() {
        assertThat(scorer.modelVersion()).isEqualTo("lr-v1");
    }
}
