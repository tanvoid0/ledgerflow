package io.ledgerflow.gateway.application;

import io.ledgerflow.gateway.application.LedgerAssistant.Proposal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Phase 4 guard: only a refund_order with a well-formed orderId survives; everything else becomes a decline. */
class ProposalTest {

    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void validRefund_survives_withTrimmedId() {
        var p = LedgerAssistant.validate(new Proposal("refund_order", " " + ID + " ", "customer asked", null));
        assertEquals("refund_order", p.action());
        assertEquals(ID, p.orderId());
        assertEquals("customer asked", p.reason());
        assertNull(p.decline());
    }

    @Test
    void blankReason_getsADefault() {
        assertEquals("Operator-requested refund.",
                LedgerAssistant.validate(new Proposal("refund_order", ID, "  ", null)).reason());
    }

    @Test
    void nonUuidOrderId_declines() {
        var p = LedgerAssistant.validate(new Proposal("refund_order", "order-7", null, null));
        assertNull(p.action());
        assertNull(p.orderId());
    }

    @Test
    void unknownAction_declines_keepingTheModelsReason() {
        var p = LedgerAssistant.validate(new Proposal("delete_everything", ID, null, "I can only refund one order."));
        assertNull(p.action());
        assertEquals("I can only refund one order.", p.decline());
    }

    @Test
    void nullOrEmpty_declineFallsBackToADefaultSentence() {
        assertEquals("No matching order to refund.", LedgerAssistant.validate(null).decline());
        assertEquals("No matching order to refund.",
                LedgerAssistant.validate(new Proposal(null, null, null, null)).decline());
    }
}
