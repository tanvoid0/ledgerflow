package io.ledgerflow.gateway.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The injection guard: only whitelisted tools and params ever shape the outbound URL. */
class QueryCatalogTest {

    @Test
    void unknownTool_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> QueryCatalog.uri("drop_table", Map.of()));
    }

    @Test
    void reviewCases_takesNoParams() {
        assertEquals("/api/v1/cases", QueryCatalog.uri("review_cases", Map.of("limit", "5", "x", "y")).toString());
    }

    @Test
    void recentPayments_clampsLimit_andIgnoresGarbage() {
        assertEquals("/api/v1/payments?limit=200", QueryCatalog.uri("recent_payments", Map.of("limit", "9999")).toString());
        assertEquals("/api/v1/payments?limit=50", QueryCatalog.uri("recent_payments", Map.of("limit", "not-a-number")).toString());
    }

    @Test
    void ordersByStatus_normalisesStatus_dropsUnknownParams() {
        assertEquals("/api/v1/orders/all?limit=50&status=CONFIRMED",
                QueryCatalog.uri("orders_by_status", Map.of("status", "confirmed", "evil", "' OR 1=1")).toString());
    }

    @Test
    void ordersByStatus_rejectsUnknownStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryCatalog.uri("orders_by_status", Map.of("status", "WHATEVER")));
    }

    @Test
    void categoryBreakdown_normalisesKind_dropsUnknownParams() {
        assertEquals("/api/v1/payments/breakdown?kind=refund",
                QueryCatalog.uri("category_breakdown", Map.of("kind", "REFUND", "evil", "' OR 1=1")).toString());
        assertEquals("/api/v1/payments/breakdown", QueryCatalog.uri("category_breakdown", Map.of()).toString());
    }

    @Test
    void categoryBreakdown_rejectsUnknownKind() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryCatalog.uri("category_breakdown", Map.of("kind", "wallets")));
    }
}
