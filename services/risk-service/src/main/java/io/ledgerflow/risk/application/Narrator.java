package io.ledgerflow.risk.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes the two sentences a fraud analyst reads next to a REVIEW: what is unusual, what to check.
 * The narrative explains a decision already made; nothing downstream reads it back.
 */
@Component
public class Narrator {

    private static final Logger log = LoggerFactory.getLogger(Narrator.class);

    private static final String PROMPT = """
            Write two sentences for a fraud analyst reviewing this payment. State what is unusual and what to check. Do not recommend a decision.

            amount: %d %s
            payments from this account in the last minute: %d
            amount z-score against this account history: %.2f
            beneficiary seen before: %s""";

    // some local models emit a reasoning block ahead of the answer; the analyst doesn't want it
    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)^\\s*<think>.*?</think>\\s*");

    private final JdbcClient db;
    private final JsonMapper json;
    private final NarratorProperties props;
    private final Optional<RestClient> client;

    public Narrator(JdbcClient db, JsonMapper json, NarratorProperties props) {
        this.db = db;
        this.json = json;
        this.props = props;
        this.client = StringUtils.hasText(props.url()) ? Optional.of(buildClient(props)) : Optional.empty();
    }

    private static RestClient buildClient(NarratorProperties props) {
        var settings = HttpClientSettings.defaults().withReadTimeout(props.timeout());
        var builder = RestClient.builder().baseUrl(props.url())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
        if (StringUtils.hasText(props.apiKey())) builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        return builder.build();
    }

    record Due(UUID paymentId, double score, String modelVersion, String features) {}

    record Written(String narrative, String generatedBy) {}

    record ChatRequest(String model, List<Message> messages, int max_tokens, double temperature) {
        record Message(String role, String content) {}
    }

    record ChatResponse(List<Choice> choices) {
        record Choice(Message message) {}
        record Message(String content) {}
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void writeCases() {
        var due = db.sql("""
                SELECT d.payment_id, d.score, d.model_version, d.features::text AS features
                  FROM risk_decision d
                  LEFT JOIN risk_case c USING (payment_id)
                 WHERE d.decision = 'REVIEW' AND c.payment_id IS NULL
                   AND d.decided_at > :cutoff
                 ORDER BY d.decided_at DESC          -- newest first: the burst an analyst is looking at now, not the backlog
                 LIMIT :n
                   FOR UPDATE OF d SKIP LOCKED
                """).param("n", props.batch())
                // a case note an hour late helps no analyst, and a load test's REVIEW backlog would keep the GPU busy for hours otherwise
                .param("cutoff", OffsetDateTime.now().minus(props.maxAge())).query(Due.class).list();

        var fallbacks = 0;
        Exception cause = null;
        for (var d : due) {
            var f = json.readValue(d.features(), Decisions.StoredFeatures.class);
            Written written = null;
            if (client.isPresent()) {
                try {
                    written = narrate(client.get(), f);
                } catch (Exception e) {
                    cause = e;
                }
            }
            if (written == null || written.narrative().isBlank()) {
                written = template(f);
                if (client.isPresent()) fallbacks++;
            }
            db.sql("INSERT INTO risk_case (payment_id, narrative, generated_by) VALUES (:id, :narrative, :generatedBy)")
                    .param("id", d.paymentId()).param("narrative", written.narrative()).param("generatedBy", written.generatedBy())
                    .update();
        }
        if (fallbacks > 0) log.warn("narrator {} unreachable, template notes for {} cases: {}", props.model(), fallbacks, cause);
    }

    private Written narrate(RestClient client, Decisions.StoredFeatures f) {
        var prompt = PROMPT.formatted(f.amountMinor(), f.currency(), f.countLastMinute(), f.amountZScore(), f.newBeneficiary() ? "no" : "yes");
        var request = new ChatRequest(props.model(), List.of(new ChatRequest.Message("user", prompt)), props.maxTokens(), 0.2);
        var response = client.post().uri("/chat/completions").body(request).retrieve().body(ChatResponse.class);
        var text = response.choices().getFirst().message().content();
        return new Written(THINK_BLOCK.matcher(text).replaceFirst("").strip(), props.model());
    }

    private Written template(Decisions.StoredFeatures f) {
        return new Written(("A %d %s payment, the %d(th) from this account in the last minute, sits at a z-score of %.2f " +
                "against its usual amounts. Its beneficiary has %s before; check the account's recent activity and confirm the beneficiary.")
                .formatted(f.amountMinor(), f.currency(), f.countLastMinute(), f.amountZScore(), f.newBeneficiary() ? "not been seen" : "been seen"),
                "template");
    }
}
