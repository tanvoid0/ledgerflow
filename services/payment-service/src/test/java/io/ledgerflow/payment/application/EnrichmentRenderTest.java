package io.ledgerflow.payment.application;

import io.ledgerflow.payment.application.Enrichment.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Validated structured output becomes a stored tag; anything missing falls back so the caller tags "uncategorised". */
class EnrichmentRenderTest {

    @Test
    void validTag_lowercasesAndTrimsCategory() {
        var t = Enrichment.render(new Tag("  Ticket-Purchase  ", "  A ticket was bought.  "));
        assertEquals("ticket-purchase", t.category());
        assertEquals("A ticket was bought.", t.reasonSummary());
    }

    @Test
    void overlongFields_areTruncated() {
        var t = Enrichment.render(new Tag("x".repeat(100), "y".repeat(400)));
        assertEquals(64, t.category().length());
        assertEquals(280, t.reasonSummary().length());
    }

    @Test
    void blankOrNull_fallsBack() {
        assertNull(Enrichment.render(new Tag("", "A payment.")));
        assertNull(Enrichment.render(new Tag("payment", "   ")));
        assertNull(Enrichment.render(null));
    }
}
