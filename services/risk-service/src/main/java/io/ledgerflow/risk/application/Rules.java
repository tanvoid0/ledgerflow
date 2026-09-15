package io.ledgerflow.risk.application;

import io.ledgerflow.events.payment.PaymentRequested;
import io.ledgerflow.risk.application.FeatureWindow.Features;
import io.ledgerflow.risk.domain.model.Decision;
import io.ledgerflow.risk.domain.model.Decision.Allow;
import io.ledgerflow.risk.domain.model.Decision.Block;
import io.ledgerflow.risk.domain.model.Decision.Review;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** What blocks a payment outright, checked before the model ever gets a say: a hard amount limit, and beneficiaries already known to be mules. */
@ConfigurationProperties(prefix = "risk.rules")
public record Rules(long amountLimitMinor, List<String> blockedBeneficiaries) {

    /**
     * Rules first, and only they can block; the model only ever asks a human to look. reviewThreshold lives at
     * risk.review-threshold, a sibling of risk.rules, so it arrives here rather than as a field of this record.
     * features is unused today; a future rule keyed on behaviour rather than amount reaches it without a new signature.
     */
    public Decision decide(PaymentRequested p, Features features, double score, String modelVersion, double reviewThreshold) {
        if (p.amount().minorUnits() > amountLimitMinor) return new Block("LIMIT");
        if (blockedBeneficiaries.contains(p.beneficiary())) return new Block("BLOCKED_BENEFICIARY");
        return score >= reviewThreshold ? new Review("score %.3f model %s".formatted(score, modelVersion)) : new Allow();
    }
}
