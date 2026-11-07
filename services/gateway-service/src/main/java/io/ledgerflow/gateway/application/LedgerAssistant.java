package io.ledgerflow.gateway.application;

import io.ledgerflow.llm.LlmClient;
import io.ledgerflow.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * Phase 1: "ask the ledger anything", read-only. The LLM picks one whitelisted query tool as validated
 * JSON (never SQL, never a URL); the gateway runs it; the LLM then phrases the real rows, streamed.
 * Nothing the LLM emits moves money or reaches a write — it chooses a read and phrases its result.
 */
@Component
public class LedgerAssistant {

    private static final Logger log = LoggerFactory.getLogger(LedgerAssistant.class);

    private static final String DISPATCH_SYSTEM = """
            You route one question about a payments ledger to exactly one read-only query tool, or decline.
            Tools:
            %s
            Reply with JSON only. To use a tool: {"tool":"<name>","params":{...}}.
            If no tool fits the question: {"tool":null,"decline":"<one short sentence>"}.
            Never invent data and never answer the question yourself here — only choose.""";

    private static final String PHRASE_SYSTEM = """
            You answer a question about a payments ledger using ONLY the JSON rows provided.
            If the rows do not contain the answer, say so plainly. Be concise: give the number or the short
            list the question asks for. Do not mention tools, JSON, or that you were given rows.""";

    private static final String PHRASE_USER = "Question: %s%nRows (JSON): %s";

    private static final String PROPOSE_SYSTEM = """
            You help an operator refund ONE ticket order. You were given rows from a read-only order lookup.
            Pick the single order the operator means and propose its refund, or decline.
            Reply with JSON only.
            To propose: {"action":"refund_order","orderId":"<a uuid taken from the rows>","reason":"<one short sentence>"}.
            If no row clearly matches: {"action":null,"decline":"<one short sentence>"}.
            You only propose - a human approves before any money moves. Never invent an orderId that is not in the rows.""";

    private final LlmClient llm;
    private final QueryCatalog catalog;

    LedgerAssistant(LlmClient llm, QueryCatalog catalog) {
        this.llm = llm;
        this.catalog = catalog;
    }

    record Dispatch(String tool, Map<String, String> params, String decline) {}

    /** A proposed refund the LLM emits (never executes). The client renders it; firing is a separate ledger-write call. */
    public record Proposal(String action, String orderId, String reason, String decline) {}

    /**
     * Phase 4 (docs/ai/0001): propose, never fire, a refund. Reuses Phase 1's read dispatch to pull candidate
     * orders, then asks for one refund command as validated JSON. The LLM holds no credential and moves nothing;
     * the proposal is handed to a human, who fires it with their own ledger-write token (POST /api/v1/orders/{id}/refund).
     */
    public Proposal propose(String question, String bearer) {
        Dispatch d = llm.chatJson(dispatchSystem(), question, Dispatch.class);
        String rows = catalog.has(d.tool()) ? safeRun(d.tool(), d.params(), bearer) : "[]";
        Proposal raw = llm.chatJson(PROPOSE_SYSTEM, "Operator: %s%nRows (JSON): %s".formatted(question, rows), Proposal.class);
        return validate(raw);
    }

    /** Pure, so the guard is unit-testable: only a refund_order with a well-formed orderId survives; anything else declines. */
    static Proposal validate(Proposal raw) {
        if (raw != null && "refund_order".equals(raw.action()) && isUuid(raw.orderId())) {
            var reason = raw.reason() != null && !raw.reason().isBlank() ? raw.reason() : "Operator-requested refund.";
            return new Proposal("refund_order", raw.orderId().strip(), reason, null);
        }
        var decline = raw != null && raw.decline() != null && !raw.decline().isBlank()
                ? raw.decline() : "No matching order to refund.";
        return new Proposal(null, null, null, decline);
    }

    private static boolean isUuid(String s) {
        if (s == null) return false;
        try {
            java.util.UUID.fromString(s.strip());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String safeRun(String tool, Map<String, String> params, String bearer) {
        try {
            return catalog.run(tool, params, bearer);
        } catch (Exception e) {
            log.warn("propose lookup {} failed: {}", tool, e.toString());
            return "[]";
        }
    }

    /** Drives one question onto the emitter: a decline, or the tool's rows phrased token by token. Always completes it. */
    public void answer(String question, String bearer, SseEmitter sse) {
        try {
            Dispatch d;
            try {
                d = llm.chatJson(dispatchSystem(), question, Dispatch.class);
            } catch (LlmException e) {
                log.warn("assistant dispatch unavailable: {}", e.toString());
                emit(sse, "The assistant is unavailable right now. Try the dashboards, or ask again shortly.");
                return;
            }

            if (!catalog.has(d.tool())) {
                emit(sse, d.decline() != null && !d.decline().isBlank()
                        ? d.decline()
                        : "I can only answer from the ledger's fraud cases, ticket orders and payments.");
                return;
            }

            String rows;
            try {
                rows = catalog.run(d.tool(), d.params(), bearer);
            } catch (Exception e) {
                log.warn("assistant tool {} failed: {}", d.tool(), e.toString());
                emit(sse, "I couldn't fetch the data to answer that.");
                return;
            }

            try {
                llm.chatStream(PHRASE_SYSTEM, PHRASE_USER.formatted(question, rows), delta -> emit(sse, delta));
            } catch (LlmException e) {
                log.warn("assistant phrasing cut off: {}", e.toString());
                emit(sse, "\n(answer cut off)");
            }
        } finally {
            sse.complete();
        }
    }

    private static String dispatchSystem() {
        var tools = QueryCatalog.TOOLS.entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        return DISPATCH_SYSTEM.formatted(tools);
    }

    private static void emit(SseEmitter sse, String text) {
        try {
            sse.send(SseEmitter.event().data(text));
        } catch (IOException | IllegalStateException e) {
            // client disconnected or the stream is already done; nothing to answer to
        }
    }
}
