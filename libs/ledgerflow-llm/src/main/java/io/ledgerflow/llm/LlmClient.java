package io.ledgerflow.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One proven way to call the agent platform (phase 0 of the AI roadmap): the Narrator's
 * OpenAI-compatible client, plus validated JSON output and model discovery.
 *
 * <p>Fails loud: any outage, empty completion or unparseable JSON throws {@link LlmException}.
 * Templating a fallback is the feature's job, not the client's — the client never invents a reply.
 */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    // some local models inline a reasoning block ahead of the answer; this proxy splits it out, but keep the strip for models that don't
    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)^\\s*<think>.*?</think>\\s*");

    private final RestClient http;
    private final JsonMapper json;
    private final LlmProperties props;
    private volatile String resolvedModel;   // discovery is a one-time lookup, then cached

    public LlmClient(LlmProperties props, JsonMapper json) {
        this(buildClient(props), props, json);
    }

    // test seam: inject a RestClient bound to MockRestServiceServer
    LlmClient(RestClient http, LlmProperties props, JsonMapper json) {
        this.http = http;
        this.props = props;
        this.json = json;
    }

    private static RestClient buildClient(LlmProperties props) {
        var settings = HttpClientSettings.defaults().withReadTimeout(props.timeout());
        var builder = RestClient.builder().baseUrl(props.url())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
        if (StringUtils.hasText(props.apiKey())) builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        // loopback-bound platform behind Docker's NAT: connect to host.docker.internal, present a Host it answers for.
        // Host is a restricted header on the JDK client, so the containers run -Djdk.httpclient.allowRestrictedHeaders=host.
        if (StringUtils.hasText(props.hostHeader())) builder.defaultHeader(HttpHeaders.HOST, props.hostHeader());
        return builder.build();
    }

    /** Free-text answer. Throws {@link LlmException} on any failure. */
    public String chat(String system, String user) {
        return content(complete(new ChatRequest(resolveModel(), messages(system, user), props.maxTokens(), 0.2)));
    }

    /**
     * Validated structured output: forces {@code response_format: json_object}, parses the reply
     * into {@code type}. A parse failure is an {@link LlmException}, never raw text acted on (ADR 0005 principle 4).
     */
    public <T> T chatJson(String system, String user, Class<T> type) {
        var text = content(complete(new JsonChatRequest(
                resolveModel(), messages(system, user), props.maxTokens(), 0.2, new ResponseFormat("json_object"))));
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new LlmException("platform returned JSON that will not parse as " + type.getSimpleName(), e);
        }
    }

    /**
     * Free-text answer streamed token by token: each content delta is handed to {@code onContent} as it arrives.
     * Throws {@link LlmException} if the stream cannot be opened; a chunk that will not parse is skipped, not fatal.
     */
    public void chatStream(String system, String user, Consumer<String> onContent) {
        var req = new StreamRequest(resolveModel(), messages(system, user), props.maxTokens(), 0.2, true);
        try {
            http.post().uri("/v1/chat/completions").body(req).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new LlmException("stream failed: " + response.getStatusCode());
                try (var reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;          // SSE framing; ignore comments and blank keep-alives
                        var data = line.substring(5).strip();
                        if (data.isEmpty() || "[DONE]".equals(data)) continue;
                        String delta;
                        try {
                            delta = json.readValue(data, StreamChunk.class).contentDelta();
                        } catch (Exception malformed) {
                            continue;                                     // a single bad/keep-alive chunk never aborts the answer
                        }
                        if (StringUtils.hasText(delta)) onContent.accept(delta);
                    }
                }
                return null;
            });
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("chat stream failed", e);
        }
    }

    /** Preferred model if the platform offers it (or a tag variant), else the first it offers. */
    private String resolveModel() {
        var cached = resolvedModel;
        if (cached != null) return cached;
        var picked = discover();
        resolvedModel = picked;
        return picked;
    }

    private String discover() {
        try {
            var models = http.get().uri("/v1/models").retrieve().body(ModelList.class);
            var ids = models == null || models.data() == null ? List.<String>of() : models.data().stream().map(ModelList.Entry::id).toList();
            if (StringUtils.hasText(props.model())) {
                for (var id : ids) if (id.equals(props.model()) || id.startsWith(props.model() + ":")) return id;  // gemma4 -> gemma4:e4b
            }
            if (!ids.isEmpty()) return ids.getFirst();
        } catch (Exception e) {
            log.warn("model discovery failed, using configured '{}': {}", props.model(), e.toString());
        }
        if (!StringUtils.hasText(props.model())) throw new LlmException("no model configured and none offered by the platform");
        return props.model();
    }

    private ChatResponse complete(Object request) {
        ChatResponse resp;
        try {
            resp = http.post().uri("/v1/chat/completions").body(request).retrieve().body(ChatResponse.class);
        } catch (Exception e) {
            throw new LlmException("chat completion failed", e);
        }
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) throw new LlmException("empty completion from " + resolvedModel);
        return resp;
    }

    private String content(ChatResponse resp) {
        var choice = resp.choices().getFirst();
        var text = choice.message() == null ? null : choice.message().content();
        if (!StringUtils.hasText(text)) {
            // the reasoning model hits this when max_tokens is spent thinking: raise maxTokens
            throw new LlmException("empty content (finish_reason=" + choice.finish_reason() + "); raise llm.max-tokens");
        }
        return THINK_BLOCK.matcher(text).replaceFirst("").strip();
    }

    private static List<Message> messages(String system, String user) {
        return StringUtils.hasText(system)
                ? List.of(new Message("system", system), new Message("user", user))
                : List.of(new Message("user", user));
    }

    record Message(String role, String content) {}

    record ChatRequest(String model, List<Message> messages, int max_tokens, double temperature) {}

    record JsonChatRequest(String model, List<Message> messages, int max_tokens, double temperature, ResponseFormat response_format) {}

    record StreamRequest(String model, List<Message> messages, int max_tokens, double temperature, boolean stream) {}

    record ResponseFormat(String type) {}

    record StreamChunk(List<Choice> choices) {
        record Choice(Delta delta) {}
        record Delta(String content) {}
        String contentDelta() {
            return choices == null || choices.isEmpty() || choices.getFirst().delta() == null ? null : choices.getFirst().delta().content();
        }
    }

    record ChatResponse(List<Choice> choices) {
        record Choice(Message message, String finish_reason) {}
        record Message(String content) {}
    }

    record ModelList(List<Entry> data) {
        record Entry(String id) {}
    }
}
