package io.ledgerflow.events;

import io.ledgerflow.events.account.EntryPosted;
import io.ledgerflow.events.balance.BalanceSnapshot;
import io.ledgerflow.events.issuer.AuthorizePayment;
import io.ledgerflow.events.issuer.PaymentAuthorized;
import io.ledgerflow.events.issuer.PaymentDeclined;
import io.ledgerflow.events.issuer.RefundPayment;
import io.ledgerflow.events.ledger.CaptureHolds;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.events.ledger.HoldClosed;
import io.ledgerflow.events.ledger.HoldRejected;
import io.ledgerflow.events.ledger.ReleaseWallets;
import io.ledgerflow.events.ledger.ReserveWallets;
import io.ledgerflow.events.payment.PaymentRequested;
import io.ledgerflow.events.settlement.CapturesIssued;
import io.ledgerflow.events.settlement.IssueCaptures;
import io.ledgerflow.events.settlement.IssueFailed;
import io.ledgerflow.events.settlement.RevokeCaptures;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The schema in resources/schemas is what the registry enforces; the records are what Jackson writes.
 * If they disagree, the gate is guarding the wrong shape. Field names must match exactly, at every level.
 */
class SchemaMatchesRecordsTest {

    // topic -> the payload records that ride on it; a topic with one type is a plain object, more is a oneOf
    static final Map<String, List<Class<? extends Record>>> TOPICS = Map.of(
            FundsHeld.TOPIC, List.of(FundsHeld.class),
            HoldRejected.TOPIC, List.of(HoldRejected.class),
            HoldClosed.TOPIC, List.of(HoldClosed.class),
            EntryPosted.TOPIC, List.of(EntryPosted.class),
            PaymentRequested.TOPIC, List.of(PaymentRequested.class),
            ReserveWallets.TOPIC, List.of(ReserveWallets.class, ReleaseWallets.class, CaptureHolds.class),
            AuthorizePayment.TOPIC, List.of(AuthorizePayment.class, RefundPayment.class),
            PaymentAuthorized.TOPIC, List.of(PaymentAuthorized.class, PaymentDeclined.class),
            IssueCaptures.TOPIC, List.of(IssueCaptures.class, RevokeCaptures.class),
            CapturesIssued.TOPIC, List.of(CapturesIssued.class, IssueFailed.class));

    // state, not events: the schema is the record's own shape, not EventEnvelope<record>
    static final Map<String, Class<? extends Record>> STATE_TOPICS = Map.of(BalanceSnapshot.TOPIC, BalanceSnapshot.class);

    @TestFactory
    Stream<DynamicTest> everyStateTopicSchemaMatchesItsRecord() {
        return STATE_TOPICS.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            var schema = new JsonMapper().readTree(getClass().getResourceAsStream("/schemas/" + e.getKey() + "-value.json"));
            assertMatches(e.getValue(), schema);
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyTopicSchemaMatchesItsRecords() {
        return TOPICS.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            var schema = new JsonMapper().readTree(getClass().getResourceAsStream("/schemas/" + e.getKey() + "-value.json"));
            assertEquals(fields(EventEnvelope.class), properties(schema));
            for (var type : e.getValue()) {
                var payload = payloadSchema(schema, typeName(type));
                assertNotNull(payload, "no payload schema titled " + typeName(type));
                assertMatches(type, payload);
            }
        }));
    }

    /** Recurses into nested records (Money, WalletRef, HeldWallet), through arrays. */
    static void assertMatches(Class<? extends Record> type, JsonNode objectSchema) {
        assertEquals(fields(type), properties(objectSchema), type.getSimpleName());
        for (var c : type.getRecordComponents()) {
            var node = objectSchema.get("properties").get(c.getName());
            if (node.has("items")) node = node.get("items");
            if (nestedRecord(c) != null) assertMatches(nestedRecord(c), node);
        }
    }

    @SuppressWarnings("unchecked")
    static Class<? extends Record> nestedRecord(RecordComponent c) {
        var t = c.getType();
        if (t == List.class) {
            var arg = ((java.lang.reflect.ParameterizedType) c.getGenericType()).getActualTypeArguments()[0];
            t = (Class<?>) arg;
        }
        return t.isRecord() ? (Class<? extends Record>) t : null;
    }

    static JsonNode payloadSchema(JsonNode schema, String title) {
        var payload = schema.get("properties").get("payload");
        if (!payload.has("oneOf")) return title.equals(payload.get("title").asString()) ? payload : null;
        for (var option : payload.get("oneOf")) if (title.equals(option.get("title").asString())) return option;
        return null;
    }

    static String typeName(Class<?> type) throws ReflectiveOperationException {
        return (String) type.getField("TYPE").get(null);
    }

    static Set<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }

    static Set<String> properties(JsonNode objectSchema) {
        return StreamSupport.stream(objectSchema.get("properties").propertyNames().spliterator(), false)
                .collect(Collectors.toSet());
    }
}
