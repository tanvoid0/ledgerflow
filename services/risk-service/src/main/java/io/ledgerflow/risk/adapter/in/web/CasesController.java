package io.ledgerflow.risk.adapter.in.web;

import io.ledgerflow.risk.application.CaseInvestigator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** What a fraud analyst works from: every REVIEW and BLOCK, newest first, with the narrative if one has been written yet. */
@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
class CasesController {

    private final JdbcClient db;
    private final CaseInvestigator investigator;

    record Case(UUID paymentId, String decision, String ruleFired, double score, String modelVersion,
                String features, String narrative, String generatedBy, Double confidence, OffsetDateTime decidedAt) {}

    record Ask(String question) {}

    record Answer(String answer) {}

    @GetMapping
    List<Case> cases() {
        return db.sql("""
                SELECT d.payment_id, d.decision, d.rule_fired, d.score, d.model_version, d.features::text AS features,
                       c.narrative, c.generated_by, c.confidence, d.decided_at
                  FROM risk_decision d
                  LEFT JOIN risk_case c USING (payment_id)
                 WHERE d.decision IN ('REVIEW', 'BLOCK')
                 ORDER BY d.decided_at DESC
                 LIMIT 50
                """).query(Case.class).list();
    }

    /** An analyst asks a follow-up about one decision; the LLM answers from that decision's record only. */
    @PostMapping("/{paymentId}/ask")
    Answer ask(@PathVariable UUID paymentId, @RequestBody Ask ask) {
        if (ask == null || !StringUtils.hasText(ask.question())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        var record = db.sql("""
                SELECT decision, rule_fired, score, model_version, features::text AS features, decided_at
                  FROM risk_decision WHERE payment_id = :id
                """).param("id", paymentId).query(CaseInvestigator.DecisionRecord.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new Answer(investigator.answer(ask.question(), record));
    }
}
