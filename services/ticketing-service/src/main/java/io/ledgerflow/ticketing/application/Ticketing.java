package io.ledgerflow.ticketing.application;

import io.ledgerflow.ticketing.adapter.out.payment.PaymentClient;
import io.ledgerflow.ticketing.config.TicketingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the catalog, the seat map and the order lifecycle. The hold is the order row with its own
 * expires_at - no holds table, no Redis. Checkout does no HTTP inside a transaction: the three steps
 * (claim the order, call payment-service, record the paymentId) each commit on their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ticketing {

    /** Open orders (HELD or PENDING_PAYMENT) one account may have at once, across all events. */
    private static final int MAX_OPEN_ORDERS = 2;

    private final JdbcClient db;
    private final JsonMapper json;
    private final PaymentClient paymentClient;
    private final TicketingProperties props;

    public record EventSummary(UUID id, String slug, String title, String subtitle, String venue, String city,
                                String category, Instant startsAt, String organiser, long priceMinor, String currency,
                                String status, long seatsTotal, long seatsFree) {}

    public record EventDetail(UUID id, String slug, String title, String subtitle, String venue, String city,
                               String category, Instant startsAt, String organiser, long priceMinor, String currency,
                               String status, List<SeatView> seats) {}

    public record SeatView(String seat, String row, Integer number, String status) {}

    public record Order(UUID id, UUID eventId, String eventTitle, UUID accountId, String username, List<String> seats,
                         long amountMinor, String currency, String status, Instant expiresAt, String requestId,
                         UUID paymentId, String failReason, Instant createdAt) {}

    record EventRow(UUID id, String slug, String title, String subtitle, String venue, String city, String category,
                     Instant startsAt, String organiser, long priceMinor, String currency, String status) {}

    record EventMoney(long priceMinor, String currency) {}

    record OrderRow(UUID id, UUID eventId, String eventTitle, String organiser, UUID accountId, String username,
                     String seats, long amountMinor, String currency, String status, Instant expiresAt,
                     String requestId, UUID paymentId, String failReason, Instant createdAt) {}

    private static final String ORDER_SELECT = """
            SELECT o.id, o.event_id, e.title AS event_title, e.organiser, o.account_id, o.username, o.seats::text AS seats,
                   o.amount_minor, o.currency, o.status, o.expires_at, o.request_id, o.payment_id, o.fail_reason, o.created_at
              FROM orders o JOIN events e ON e.id = o.event_id
            """;

    public List<EventSummary> events() {
        return db.sql("""
                SELECT e.id, e.slug, e.title, e.subtitle, e.venue, e.city, e.category, e.starts_at, e.organiser,
                       e.price_minor, e.currency, e.status,
                       count(s.*) AS seats_total, count(s.*) FILTER (WHERE s.status = 'FREE') AS seats_free
                  FROM events e JOIN seats s ON s.event_id = e.id
                 WHERE e.status = 'ON_SALE'
                 GROUP BY e.id
                 ORDER BY e.starts_at
                """).query(EventSummary.class).list();
    }

    public Optional<EventDetail> event(UUID id) {
        var event = db.sql("""
                SELECT id, slug, title, subtitle, venue, city, category, starts_at, organiser, price_minor, currency, status
                  FROM events WHERE id = :id
                """).param("id", id).query(EventRow.class).optional();
        if (event.isEmpty()) return Optional.empty();
        var seats = db.sql("""
                SELECT seat, row_label AS "row", seat_number AS "number", status FROM seats
                 WHERE event_id = :id ORDER BY row_label, seat_number
                """).param("id", id).query(SeatView.class).list();
        var e = event.get();
        return Optional.of(new EventDetail(e.id(), e.slug(), e.title(), e.subtitle(), e.venue(), e.city(), e.category(),
                e.startsAt(), e.organiser(), e.priceMinor(), e.currency(), e.status(), seats));
    }

    @Transactional
    public EventSummary createEvent(String title, String subtitle, String venue, String city, String category,
                                     Instant startsAt, String organiser, long priceMinor, String currency, int rows, int seatsPerRow) {
        var id = UUID.randomUUID();
        var slug = slugify(title) + "-" + id.toString().substring(0, 8);
        db.sql("""
                INSERT INTO events (id, slug, title, subtitle, venue, city, category, starts_at, organiser, price_minor, currency)
                VALUES (:id, :slug, :title, :subtitle, :venue, :city, :category, :startsAt, :organiser, :priceMinor, :currency)
                """)
                .param("id", id).param("slug", slug).param("title", title).param("subtitle", subtitle)
                .param("venue", venue).param("city", city).param("category", category).param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
                .param("organiser", organiser).param("priceMinor", priceMinor).param("currency", currency)
                .update();
        for (var r = 1; r <= rows; r++) {
            var rowLabel = String.valueOf((char) ('A' + r - 1));
            for (var s = 1; s <= seatsPerRow; s++) {
                db.sql("INSERT INTO seats (event_id, seat, row_label, seat_number) VALUES (:event, :seat, :row, :number)")
                        .param("event", id).param("seat", rowLabel + s).param("row", rowLabel).param("number", s).update();
            }
        }
        var total = (long) rows * seatsPerRow;
        return new EventSummary(id, slug, title, subtitle, venue, city, category, startsAt, organiser, priceMinor, currency,
                "ON_SALE", total, total);
    }

    @Transactional
    public void cancelEvent(UUID id) {
        var cancelled = db.sql("UPDATE events SET status = 'CANCELLED' WHERE id = :id AND status = 'ON_SALE'")
                .param("id", id).update();
        if (cancelled == 0) throw new ErrorResponseException(HttpStatus.NOT_FOUND);
        // SOLD stays sold, no refunds: HELD orders give their seats back; PENDING_PAYMENT keeps them for its outcome
        db.sql("""
                WITH e AS (UPDATE orders SET status = 'EXPIRED', updated_at = now() WHERE event_id = :id AND status = 'HELD' RETURNING id)
                UPDATE seats SET status = 'FREE', order_id = NULL WHERE order_id IN (SELECT id FROM e) AND status = 'HELD'
                """).param("id", id).update();
    }

    /** One transaction: the order row and the row-locked seat claim rise or fall together. */
    @Transactional
    public UUID hold(UUID eventId, UUID accountId, String username, List<String> seats) {
        // FOR SHARE: a concurrent cancelEvent waits for this hold to commit (and then expires it), or this hold sees CANCELLED
        var event = db.sql("SELECT price_minor, currency FROM events WHERE id = :id AND status = 'ON_SALE' FOR SHARE")
                .param("id", eventId).query(EventMoney.class).optional()
                .orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND));
        // the lock queues one account's parallel holds, so they cannot all count the same open orders and all insert
        db.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", accountId.getMostSignificantBits() ^ accountId.getLeastSignificantBits()).query().listOfRows();
        var open = db.sql("SELECT count(*) FROM orders WHERE account_id = :account AND status IN ('HELD', 'PENDING_PAYMENT')")
                .param("account", accountId).query(Long.class).single();
        if (open >= MAX_OPEN_ORDERS) throw new ErrorResponseException(HttpStatus.CONFLICT);
        var orderId = UUID.randomUUID();
        var amount = event.priceMinor() * seats.size();
        db.sql("""
                INSERT INTO orders (id, event_id, account_id, username, seats, amount_minor, currency, status, expires_at)
                VALUES (:id, :event, :account, :username, CAST(:seats AS jsonb), :amount, :currency, 'HELD', :expires)
                """)
                .param("id", orderId).param("event", eventId).param("account", accountId).param("username", username)
                .param("seats", json.writeValueAsString(seats)).param("amount", amount).param("currency", event.currency())
                .param("expires", Instant.now().plus(props.holdTtl()).atOffset(ZoneOffset.UTC))
                .update();
        // READ COMMITTED re-evaluates status = 'FREE' after the lock: the loser of a race sees 0 rows, not a wrong seat
        var claimed = db.sql("""
                UPDATE seats SET status = 'HELD', order_id = :order
                 WHERE (event_id, seat) IN (
                     SELECT event_id, seat FROM seats WHERE event_id = :event AND seat IN (:seats) ORDER BY seat FOR UPDATE)
                   AND status = 'FREE'
                """)
                .param("order", orderId).param("event", eventId).param("seats", seats).update();
        if (claimed != seats.size()) throw new ErrorResponseException(HttpStatus.CONFLICT);   // rolls back the insert too
        return orderId;
    }

    /**
     * No transaction wraps this method: PlaceHold.java explains why - a slow payment-service must
     * not pin a database connection for the whole of the wait. Each step commits on its own.
     */
    public Order checkout(UUID orderId, UUID accountId, String authorization, String requestId) {
        var claimed = db.sql("""
                UPDATE orders SET status = 'PENDING_PAYMENT', request_id = :requestId, updated_at = now()
                 WHERE id = :id AND account_id = :account AND status = 'HELD' AND expires_at > now()
                   AND EXISTS (SELECT 1 FROM events e WHERE e.id = orders.event_id AND e.status = 'ON_SALE')
                """)
                .param("id", orderId).param("account", accountId).param("requestId", requestId).update();
        if (claimed == 0) throw new ErrorResponseException(HttpStatus.CONFLICT);
        var order = orderRow(orderId).orElseThrow();
        UUID paymentId;
        try {
            paymentId = paymentClient.pay(authorization, requestId, accountId, order.amountMinor(), order.currency(), order.organiser());
        } catch (HttpClientErrorException e) {
            // a 4xx: payment-service refused it and rolled back, so no payment exists and the hold is the customer's again
            db.sql("UPDATE orders SET status = 'HELD', updated_at = now() WHERE id = :id AND status = 'PENDING_PAYMENT' AND payment_id IS NULL")
                    .param("id", orderId).update();
            throw new ErrorResponseException(HttpStatus.BAD_GATEWAY);
        } catch (RestClientException e) {
            // 5xx, reset, timeout: a payment may have committed. The outcome settles the order by request_id, or the sweep does
            log.warn("checkout {}: no clean answer from payment-service, order stays PENDING_PAYMENT", orderId, e);
            return toOrder(orderRow(orderId).orElseThrow());
        }
        // guarded: an outcome that already settled the order by request_id is never overwritten
        db.sql("UPDATE orders SET payment_id = :paymentId WHERE id = :id AND payment_id IS NULL AND status = 'PENDING_PAYMENT'")
                .param("paymentId", paymentId).param("id", orderId).update();
        return toOrder(orderRow(orderId).orElseThrow());
    }

    /**
     * A HELD order past its expiry frees its seats. PENDING_PAYMENT waits on the outcome - unless checkout never
     * learned a paymentId and nothing has arrived for 10 minutes: payment-service most likely never made one.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void sweep() {
        db.sql("""
                WITH e AS (UPDATE orders SET status = 'EXPIRED', updated_at = now() WHERE status = 'HELD' AND expires_at < now() RETURNING id)
                UPDATE seats SET status = 'FREE', order_id = NULL WHERE order_id IN (SELECT id FROM e) AND status = 'HELD'
                """).update();
        // ponytail: a capture landing more than 10 min after an unanswered checkout finds the order FAILED and is dropped:
        // charged, no ticket. Refunding unmatched captures is the upgrade path if payment-service outages ever last that long.
        db.sql("""
                WITH t AS (UPDATE orders SET status = 'FAILED', fail_reason = 'TIMED_OUT', updated_at = now()
                            WHERE status = 'PENDING_PAYMENT' AND payment_id IS NULL AND updated_at < now() - interval '10 minutes'
                            RETURNING id)
                UPDATE seats SET status = 'FREE', order_id = NULL WHERE order_id IN (SELECT id FROM t) AND status = 'HELD'
                """).update();
    }

    /**
     * Which order an outcome settles. The correlation id is the caller's own X-Request-Id, so it is never enough on its
     * own: the payment must also carry this order's account, amount and currency, and only PENDING_PAYMENT matches.
     */
    private static final String OUTCOME_MATCH = """
            (payment_id = :paymentId OR (payment_id IS NULL AND request_id = :correlationId))
              AND account_id = :account AND amount_minor = :amount AND currency = :currency
              AND status = 'PENDING_PAYMENT'
            """;

    /** Called from the inbox: idempotent twice over, once by event_id and again here by order status. */
    public void confirm(UUID paymentId, String correlationId, UUID accountId, long amountMinor, String currency) {
        db.sql("""
                WITH o AS (UPDATE orders SET status = 'CONFIRMED', payment_id = COALESCE(payment_id, :paymentId), updated_at = now()
                            WHERE """ + OUTCOME_MATCH + """
                            RETURNING id)
                UPDATE seats SET status = 'SOLD' WHERE order_id IN (SELECT id FROM o)
                """).param("paymentId", paymentId).param("correlationId", correlationId)
                .param("account", accountId).param("amount", amountMinor).param("currency", currency).update();
    }

    public void fail(UUID paymentId, String correlationId, UUID accountId, long amountMinor, String currency, String reason) {
        db.sql("""
                WITH o AS (UPDATE orders SET status = 'FAILED', fail_reason = :reason, payment_id = COALESCE(payment_id, :paymentId), updated_at = now()
                            WHERE """ + OUTCOME_MATCH + """
                            RETURNING id)
                UPDATE seats SET status = 'FREE', order_id = NULL WHERE order_id IN (SELECT id FROM o) AND status = 'HELD'
                """).param("paymentId", paymentId).param("correlationId", correlationId)
                .param("account", accountId).param("amount", amountMinor).param("currency", currency).param("reason", reason).update();
    }

    public Optional<Order> order(UUID id) {
        return orderRow(id).map(this::toOrder);
    }

    /** Newest first, the caller's own. */
    public List<Order> ordersForAccount(UUID accountId) {
        return db.sql(ORDER_SELECT + " WHERE o.account_id = :account ORDER BY o.created_at DESC")
                .param("account", accountId).query(OrderRow.class).list().stream().map(this::toOrder).toList();
    }

    /** account-read's view: every order, optionally filtered by status. */
    public List<Order> ordersAll(String status, int limit) {
        var spec = db.sql(ORDER_SELECT + (status == null ? "" : " WHERE o.status = :status") + " ORDER BY o.created_at DESC LIMIT :limit")
                .param("limit", limit);
        if (status != null) spec = spec.param("status", status);
        return spec.query(OrderRow.class).list().stream().map(this::toOrder).toList();
    }

    private Optional<OrderRow> orderRow(UUID id) {
        return db.sql(ORDER_SELECT + " WHERE o.id = :id").param("id", id).query(OrderRow.class).optional();
    }

    private Order toOrder(OrderRow r) {
        return new Order(r.id(), r.eventId(), r.eventTitle(), r.accountId(), r.username(),
                List.of(json.readValue(r.seats(), String[].class)), r.amountMinor(), r.currency(), r.status(),
                r.expiresAt(), r.requestId(), r.paymentId(), r.failReason(), r.createdAt());
    }

    private static String slugify(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }
}
