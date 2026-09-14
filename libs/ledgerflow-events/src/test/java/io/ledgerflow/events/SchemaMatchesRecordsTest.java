package io.ledgerflow.events;

import io.ledgerflow.events.ledger.FundsHeld;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The schema in resources/schemas is what the registry enforces; the records are what Jackson writes.
 * If they disagree, the gate is guarding the wrong shape. Field names must match exactly.
 */
class SchemaMatchesRecordsTest {

    static final JsonNode SCHEMA = new JsonMapper().readTree(
            SchemaMatchesRecordsTest.class.getResourceAsStream(
                    "/schemas/" + FundsHeld.TOPIC + "-value.json"));

    @Test
    void envelopeFieldsMatchSchema() {
        assertEquals(fields(EventEnvelope.class), properties(SCHEMA));
    }

    @Test
    void payloadFieldsMatchSchema() {
        var payload = SCHEMA.get("properties").get("payload");
        assertEquals(fields(FundsHeld.class), properties(payload));
        assertEquals(fields(WalletRef.class), properties(payload.get("properties").get("wallets").get("items")));
        assertEquals(fields(Money.class), properties(payload.get("properties").get("totalAmount")));
    }

    static Set<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet());
    }

    static Set<String> properties(JsonNode objectSchema) {
        return StreamSupport.stream(objectSchema.get("properties").propertyNames().spliterator(), false)
                .collect(Collectors.toSet());
    }
}
