package io.ledgerflow.risk.application;

import io.ledgerflow.llm.LlmClient;
import io.ledgerflow.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Lets an analyst interrogate ONE risk decision in English. The LLM answers from that decision's own
 * record and nothing else — it never fetches more, never recommends block or approve (the decision is
 * already made, ADR 0005), and an outage degrades to a plain "unavailable", never an error page.
 */
@Component
public class CaseInvestigator {

    private static final Logger log = LoggerFactory.getLogger(CaseInvestigator.class);

    private static final String SYSTEM = """
            You answer a fraud analyst's question about ONE risk decision, using ONLY the decision record below.
            If the record does not contain the answer, say so plainly. The decision has already been made —
            explain the record, never recommend whether to block or approve. Be concise.""";

    private static final String USER = """
            Decision record:
            decision: %s
            rule fired: %s
            score: %s
            model: %s
            decided at: %s
            features: %s

            Analyst question: %s""";

    private final LlmClient llm;

    CaseInvestigator(LlmClient llm) {
        this.llm = llm;
    }

    public record DecisionRecord(String decision, String ruleFired, double score, String modelVersion,
                                 OffsetDateTime decidedAt, String features) {}

    public String answer(String question, DecisionRecord r) {
        try {
            return llm.chat(SYSTEM, USER.formatted(r.decision(), r.ruleFired(), r.score(), r.modelVersion(), r.decidedAt(), r.features(), question));
        } catch (LlmException e) {
            log.warn("case follow-up unavailable: {}", e.toString());
            return "The assistant is unavailable right now.";
        }
    }
}
