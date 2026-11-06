package io.ledgerflow.risk.application;

import io.ledgerflow.risk.application.Narrator.CaseNote;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Validated structured output becomes a stored note; anything missing falls back to a template. */
class NarratorRenderTest {

    @Test
    void validNote_composesNarrative_andClampsConfidence() {
        var w = Narrator.render(new CaseNote("Amount is 10x the usual.", "Confirm the beneficiary.", 1.4), "gemma4");
        assertEquals("Amount is 10x the usual. Confirm the beneficiary.", w.narrative());
        assertEquals("gemma4", w.generatedBy());
        assertEquals(1.0, w.confidence());   // 1.4 clamped into 0..1
    }

    @Test
    void blankField_fallsBackToTemplate() {
        assertNull(Narrator.render(new CaseNote("", "Confirm the beneficiary.", 0.5), "gemma4"));
        assertNull(Narrator.render(new CaseNote("Amount is unusual.", "  ", 0.5), "gemma4"));
        assertNull(Narrator.render(null, "gemma4"));
    }
}
