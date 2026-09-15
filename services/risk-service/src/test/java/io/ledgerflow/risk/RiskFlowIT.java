package io.ledgerflow.risk;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.payment.PaymentRequested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.JacksonMapperUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** A payment requested is a payment scored, end to end: a blocked beneficiary, an account that trips the model, and the API that reads both back. */
@SpringBootTest(properties = "risk.narrator.url=")   // never call out to Ollama from a test run
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RiskFlowIT {

    static final JsonMapper json = JacksonMapperUtils.enhancedJsonMapper();

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcClient db;
    @Autowired MockMvcTester mvc;

    @Test
    void aBlockedBeneficiaryIsBlocked() {
        var account = UUID.randomUUID();
        var p = new PaymentRequested(UUID.randomUUID(), account, List.of("A-12"), Money.gbp(100), "mule-1");
        send(p);

        await().untilAsserted(() -> assertThat(decision(p.paymentId())).isEqualTo("BLOCK"));
        assertThat(ruleFired(p.paymentId())).isEqualTo("BLOCKED_BENEFICIARY");
    }

    @Test
    void eightPaymentsInARowTripTheModelAndGetANarrative() {
        var account = UUID.randomUUID();
        // seven modest, varied amounts build a history with a real (non-zero) spread, then one far outside it
        var amounts = List.of(100L, 150L, 200L, 250L, 300L, 350L, 400L, 5000L);
        var payments = amounts.stream().map(a -> new PaymentRequested(UUID.randomUUID(), account, List.of("A-12"), Money.gbp(a), "b-" + a)).toList();
        payments.forEach(this::send);
        var last = payments.getLast().paymentId();

        // PaymentRequested is keyed by paymentId, so these 8 can land on different partitions and be scored out of
        // order; countLastMinute for "last" is therefore >= 1, not necessarily 8 - only that it landed on a REVIEW.
        await().untilAsserted(() -> assertThat(decision(last)).isEqualTo("REVIEW"));
        assertThat(countLastMinute(last)).isGreaterThan(0);
        await().untilAsserted(() -> assertThat(generatedBy(last)).isEqualTo("template"));   // narrator.url is blank in this run

        assertThat(mvc.get().uri("/api/v1/cases")).hasStatusOk()
                .bodyJson().extractingPath("$[?(@.paymentId=='" + last + "')].generatedBy").isEqualTo(List.of("template"));
    }

    private void send(PaymentRequested p) {
        var envelope = EventEnvelope.of(PaymentRequested.TYPE, p.paymentId(), 1, "req-1", "req-1", p);
        kafka.send(PaymentRequested.TOPIC, p.paymentId().toString(), json.writeValueAsString(envelope));
    }

    private String decision(UUID paymentId) {
        return db.sql("SELECT decision FROM risk_decision WHERE payment_id = :id").param("id", paymentId)
                .query(String.class).optional().orElse(null);
    }

    private String ruleFired(UUID paymentId) {
        return db.sql("SELECT rule_fired FROM risk_decision WHERE payment_id = :id").param("id", paymentId)
                .query(String.class).single();
    }

    private String generatedBy(UUID paymentId) {
        return db.sql("SELECT generated_by FROM risk_case WHERE payment_id = :id").param("id", paymentId)
                .query(String.class).optional().orElse(null);
    }

    private int countLastMinute(UUID paymentId) {
        return db.sql("SELECT (features->>'countLastMinute')::int FROM risk_decision WHERE payment_id = :id")
                .param("id", paymentId).query(Integer.class).single();
    }
}
