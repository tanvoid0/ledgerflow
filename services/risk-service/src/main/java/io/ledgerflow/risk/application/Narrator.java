package io.ledgerflow.risk.application;

import io.ledgerflow.llm.LlmClient;
import io.ledgerflow.llm.LlmException;
import io.ledgerflow.llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Writes the case note a fraud analyst reads next to a REVIEW: what is unusual, what to check, and how
 * unusual the model finds it. Structured output ({@link CaseNote}) is validated before the insert — in a
 * financial context we never store free text we have not parsed. The note explains a decision already
 * made; nothing downstream reads it back (ADR 0005). Model unreachable or output invalid → template.
 */
@Component
public class Narrator {

    private static final Logger log = LoggerFactory.getLogger(Narrator.class);

    private static final String SYSTEM = """
            You write a short fraud-review case note for an analyst, as JSON with exactly these fields:
            whatIsUnusual (one sentence), whatToCheck (one sentence), confidence (a number 0..1 — how unusual this looks).
            State facts about the payment. Do not recommend a decision. Reply with JSON only.""";

    private static final String FACTS = """
            amount: %d %s
            payments from this account in the last minute: %d
            amount z-score against this account history: %.2f
            beneficiary seen before: %s""";

    private final JdbcClient db;
    private final JsonMapper json;
    private final LlmClient llm;
    private final NarratorProperties props;
    private final String model;
    private final boolean llmConfigured;

    public Narrator(JdbcClient db, JsonMapper json, LlmClient llm, NarratorProperties props, LlmProperties llmProps) {
        this.db = db;
        this.json = json;
        this.llm = llm;
        this.props = props;
        this.model = llmProps.model();
        this.llmConfigured = StringUtils.hasText(llmProps.url());   // blank url = template notes only, no network call
    }

    record Due(UUID paymentId, double score, String modelVersion, String features) {}

    record CaseNote(String whatIsUnusual, String whatToCheck, double confidence) {}

    record Written(String narrative, String generatedBy, Double confidence) {}

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void writeCases() {
        var due = db.sql("""
                SELECT d.payment_id, d.score, d.model_version, d.features::text AS features
                  FROM risk_decision d
                  LEFT JOIN risk_case c USING (payment_id)
                 WHERE d.decision = 'REVIEW' AND c.payment_id IS NULL
                   AND d.decided_at > :cutoff
                 ORDER BY d.decided_at DESC          -- newest first: the burst an analyst is looking at now, not the backlog
                 LIMIT :n
                   FOR UPDATE OF d SKIP LOCKED
                """).param("n", props.batch())
                .param("cutoff", OffsetDateTime.now().minus(props.maxAge())).query(Due.class).list();

        var fallbacks = 0;
        Exception cause = null;
        for (var d : due) {
            var f = json.readValue(d.features(), Decisions.StoredFeatures.class);
            Written written = null;
            if (llmConfigured) {
                try {
                    written = render(llm.chatJson(SYSTEM, FACTS.formatted(f.amountMinor(), f.currency(),
                            f.countLastMinute(), f.amountZScore(), f.newBeneficiary() ? "no" : "yes"), CaseNote.class), model);
                } catch (LlmException e) {
                    cause = e;
                }
            }
            if (written == null) {
                written = template(f);
                if (llmConfigured) fallbacks++;
            }
            db.sql("INSERT INTO risk_case (payment_id, narrative, generated_by, confidence) VALUES (:id, :narrative, :generatedBy, :confidence)")
                    .param("id", d.paymentId()).param("narrative", written.narrative())
                    .param("generatedBy", written.generatedBy()).param("confidence", written.confidence())
                    .update();
        }
        if (fallbacks > 0) log.warn("narrator {} unreachable or invalid, template notes for {} cases: {}", model, fallbacks, cause);
    }

    /** Pure: validated structured output becomes a stored note, or null so the caller falls back to a template. */
    static Written render(CaseNote note, String model) {
        if (note == null || !StringUtils.hasText(note.whatIsUnusual()) || !StringUtils.hasText(note.whatToCheck())) return null;
        var narrative = (note.whatIsUnusual().strip() + " " + note.whatToCheck().strip()).strip();
        return new Written(narrative, model, Math.clamp(note.confidence(), 0.0, 1.0));
    }

    private Written template(Decisions.StoredFeatures f) {
        return new Written(("A %d %s payment, the %d(th) from this account in the last minute, sits at a z-score of %.2f " +
                "against its usual amounts. Its beneficiary has %s before; check the account's recent activity and confirm the beneficiary.")
                .formatted(f.amountMinor(), f.currency(), f.countLastMinute(), f.amountZScore(), f.newBeneficiary() ? "not been seen" : "been seen"),
                "template", null);
    }
}
