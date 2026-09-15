package io.ledgerflow.risk.adapter.in.web;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** What a fraud analyst works from: every REVIEW and BLOCK, newest first, with the narrative if one has been written yet. */
@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
class CasesController {

    private final JdbcClient db;

    record Case(UUID paymentId, String decision, String ruleFired, double score, String modelVersion,
                String features, String narrative, String generatedBy, OffsetDateTime decidedAt) {}

    @GetMapping
    List<Case> cases() {
        return db.sql("""
                SELECT d.payment_id, d.decision, d.rule_fired, d.score, d.model_version, d.features::text AS features,
                       c.narrative, c.generated_by, d.decided_at
                  FROM risk_decision d
                  LEFT JOIN risk_case c USING (payment_id)
                 WHERE d.decision IN ('REVIEW', 'BLOCK')
                 ORDER BY d.decided_at DESC
                 LIMIT 50
                """).query(Case.class).list();
    }
}
