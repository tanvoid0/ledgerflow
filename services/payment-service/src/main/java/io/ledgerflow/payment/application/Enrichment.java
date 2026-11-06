package io.ledgerflow.payment.application;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.llm.LlmClient;
import io.ledgerflow.llm.LlmException;
import io.ledgerflow.llm.LlmProperties;
import io.ledgerflow.starter.messaging.Inbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * Phase 3: an async, off-the-money-path read model. A terminal payment/refund event is handed to the LLM,
 * which returns a validated {@link Tag} ({category, reasonSummary}); we project it so the assistant can
 * group refunds (or payments) by category. A read model only — a wrong tag embarrasses a dashboard, never
 * a payment (ADR 0005). Never on the money path, never a write back to a payment. Platform unreachable or
 * output invalid → the row still lands, tagged "uncategorised": the stream never stalls on a token.
 */
@Service
public class Enrichment {

    private static final Logger log = LoggerFactory.getLogger(Enrichment.class);

    private static final String SYSTEM = """
            You label a settled ledger event for an operations dashboard, as JSON with exactly these fields:
            category (one or two words, lower-case, e.g. "ticket-purchase", "event-cancellation", "high-value"),
            reasonSummary (one short sentence a non-technical reader understands).
            State what the event is; do not advise and do not invent details you were not given. Reply with JSON only.""";

    private static final String FACTS = """
            kind: %s
            amount: %d %s
            note: %s""";

    // ponytail: the signal is thin — the outcome event carries only amount + kind, not the beneficiary (dropped
    // after the Requested state). Good enough to prove the async-enrichment pattern; richer tags would mean joining
    // PaymentRequested over the stream, deferred (noted in the roadmap).
    private static final String PAYMENT_NOTE = "a payment that settled successfully";
    private static final String REFUND_NOTE = "a settled payment reversed; the buyer's money was returned";

    public static final String PAYMENT = "payment";
    public static final String REFUND = "refund";

    private final JdbcClient db;
    private final Inbox inbox;
    private final LlmClient llm;
    private final boolean llmConfigured;

    public Enrichment(JdbcClient db, Inbox inbox, LlmClient llm, LlmProperties llmProps) {
        this.db = db;
        this.inbox = inbox;
        this.llm = llm;
        this.llmConfigured = StringUtils.hasText(llmProps.url());   // blank url = tag "uncategorised", no network call
    }

    /** The validated structured output. */
    record Tag(String category, String reasonSummary) {}

    /** One grouped row for the assistant to phrase: how many, and the total, per category. */
    public record Breakdown(String category, long count, long totalMinor) {}

    /**
     * Tag the event and project it once. The LLM call is before {@link Inbox#once} on purpose: it must not
     * run inside the dedupe transaction (a Kafka consumer thread would hold a DB row while waiting on a token).
     */
    public void project(EventEnvelope<?> cause, UUID paymentId, String kind, long amountMinor, String currency) {
        var tag = tag(kind, amountMinor, currency);
        inbox.once(cause, () -> db.sql("""
                        INSERT INTO payment_enrichment (payment_id, kind, category, reason_summary, amount_minor, currency)
                        VALUES (:id, :kind, :category, :reason, :amount, :currency)
                        ON CONFLICT (payment_id, kind) DO NOTHING
                        """)
                .param("id", paymentId).param("kind", kind)
                .param("category", tag.category()).param("reason", tag.reasonSummary())
                .param("amount", amountMinor).param("currency", currency)
                .update());
    }

    private Tag tag(String kind, long amountMinor, String currency) {
        if (llmConfigured) {
            try {
                var validated = render(llm.chatJson(SYSTEM,
                        FACTS.formatted(kind, amountMinor, currency, kind.equals(REFUND) ? REFUND_NOTE : PAYMENT_NOTE), Tag.class));
                if (validated != null) return validated;
            } catch (LlmException e) {
                log.warn("enrichment: platform unreachable or invalid for a {}, tagging uncategorised: {}", kind, e.toString());
            }
        }
        return new Tag("uncategorised", kind.equals(REFUND) ? "A refund with no category yet." : "A payment with no category yet.");
    }

    /** Pure: validated structured output becomes a tag, or null so the caller falls back. Keeps the whitelist of what we store. */
    static Tag render(Tag tag) {
        if (tag == null || !StringUtils.hasText(tag.category()) || !StringUtils.hasText(tag.reasonSummary())) return null;
        var category = tag.category().strip().toLowerCase();
        var reason = tag.reasonSummary().strip();
        if (category.length() > 64) category = category.substring(0, 64);
        if (reason.length() > 280) reason = reason.substring(0, 280);
        return new Tag(category, reason);
    }

    /** payments or refunds grouped by category, newest-heaviest first. kind null = both kinds together. */
    public List<Breakdown> breakdown(String kind) {
        return db.sql("""
                        SELECT category, count(*) AS count, sum(amount_minor) AS total_minor
                          FROM payment_enrichment
                         WHERE (:kind IS NULL OR kind = :kind)
                         GROUP BY category
                         ORDER BY count DESC, category
                        """)
                .param("kind", kind)
                .query(Breakdown.class).list();
    }
}
