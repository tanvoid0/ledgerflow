package io.ledgerflow.gateway.application;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The injection guard for the ledger assistant: a fixed set of read-only query tools, each a GET to one
 * of the gateway's own public read endpoints with only whitelisted, sanitised params. The LLM chooses a
 * tool name and params; it never sees or emits a URL or SQL, and an unknown tool or param reaches nothing.
 */
@Component
public class QueryCatalog {

    private static final Set<String> ORDER_STATUSES =
            Set.of("HELD", "PENDING_PAYMENT", "CONFIRMED", "CANCELLED", "REFUNDED", "EXPIRED", "FAILED");

    /** name -> what the LLM is told it does; the description doubles as the prompt's tool list. */
    private static final Set<String> ENRICHMENT_KINDS = Set.of("payment", "refund");

    public static final Map<String, String> TOOLS = Map.of(
            "review_cases", "fraud cases flagged REVIEW or BLOCK, newest 50. No params.",
            "orders_by_status", "ticket orders filtered by status. params: status (one of " + ORDER_STATUSES + "), limit (1-200).",
            "recent_payments", "the newest payments with amount and state. params: limit (1-200).",
            "category_breakdown", "settled payments or refunds grouped by LLM-derived category, each with a count and total amount. "
                    + "Use for 'break refunds down by category/reason'. params: kind (one of " + ENRICHMENT_KINDS + "; omit for both).");

    private final RestClient api;

    QueryCatalog(RestClient ledgerApi) {
        this.api = ledgerApi;
    }

    public boolean has(String tool) {
        return tool != null && TOOLS.containsKey(tool);
    }

    /** Runs the tool and returns the raw JSON rows for the LLM to phrase. The bearer is the caller's own token. */
    public String run(String tool, Map<String, String> params, String bearer) {
        var uri = uri(tool, params == null ? Map.of() : params);
        return api.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer).retrieve().body(String.class);
    }

    /** Pure, so the whitelisting is unit-testable without a server: unknown params dropped, status validated, limit clamped. */
    static URI uri(String tool, Map<String, String> params) {
        return switch (tool) {
            case "review_cases" -> URI.create("/api/v1/cases");
            case "recent_payments" -> UriComponentsBuilder.fromPath("/api/v1/payments")
                    .queryParam("limit", limit(params)).build().toUri();
            case "orders_by_status" -> {
                var b = UriComponentsBuilder.fromPath("/api/v1/orders/all").queryParam("limit", limit(params));
                status(params).ifPresent(s -> b.queryParam("status", s));
                yield b.build().toUri();
            }
            case "category_breakdown" -> {
                var b = UriComponentsBuilder.fromPath("/api/v1/payments/breakdown");
                kind(params).ifPresent(k -> b.queryParam("kind", k));
                yield b.build().toUri();
            }
            default -> throw new IllegalArgumentException("unknown tool: " + tool);
        };
    }

    private static int limit(Map<String, String> params) {
        try {
            return Math.clamp(Long.parseLong(params.getOrDefault("limit", "50")), 1, 200);
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    private static Optional<String> status(Map<String, String> params) {
        var s = params.get("status");
        if (s == null) return Optional.empty();
        var up = s.strip().toUpperCase();
        if (!ORDER_STATUSES.contains(up)) throw new IllegalArgumentException("unknown order status: " + s);
        return Optional.of(up);
    }

    private static Optional<String> kind(Map<String, String> params) {
        var k = params.get("kind");
        if (k == null) return Optional.empty();
        var low = k.strip().toLowerCase();
        if (!ENRICHMENT_KINDS.contains(low)) throw new IllegalArgumentException("unknown enrichment kind: " + k);
        return Optional.of(low);
    }
}
