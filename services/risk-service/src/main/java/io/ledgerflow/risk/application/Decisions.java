package io.ledgerflow.risk.application;

import io.ledgerflow.risk.domain.model.Decision;
import io.ledgerflow.risk.domain.model.Decision.Block;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * The append-only record of what was decided (V2__risk_decision.sql: no UPDATE anywhere near this table).
 * features carries its own context - amount, beneficiary, the account's behaviour at the time - so the
 * narrator needs no second table to write its prompt from.
 */
@Component
@RequiredArgsConstructor
public class Decisions {

    private final JdbcClient db;
    private final JsonMapper json;
    private final MeterRegistry meters;

    /** The shape written into the features column and read back out of it: everything the narrator's prompt needs, so it needs no second table. */
    public record StoredFeatures(long amountMinor, String currency, String beneficiary,
                                  int countLastMinute, double amountZScore, boolean newBeneficiary) {}

    public void record(UUID paymentId, Decision decision, Object features, double score, String modelVersion) {
        db.sql("""
                INSERT INTO risk_decision (payment_id, decision, rule_fired, model_version, score, features)
                VALUES (:paymentId, :decision, :ruleFired, :modelVersion, :score, CAST(:features AS jsonb))
                """)
                .param("paymentId", paymentId)
                .param("decision", name(decision))
                .param("ruleFired", decision instanceof Block b ? b.rule() : null)
                .param("modelVersion", modelVersion)
                .param("score", score)
                .param("features", json.writeValueAsString(features))
                .update();
        meters.counter("risk.decisions", "decision", name(decision)).increment();
    }

    // the reason string (Review) goes nowhere: score and modelVersion already say everything it says
    private static String name(Decision d) {
        return switch (d) {
            case Decision.Allow a -> "ALLOW";
            case Decision.Review r -> "REVIEW";
            case Decision.Block b -> "BLOCK";
        };
    }
}
