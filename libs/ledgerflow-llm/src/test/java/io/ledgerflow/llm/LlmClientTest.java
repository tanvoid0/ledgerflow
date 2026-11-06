package io.ledgerflow.llm;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmClientTest {

    record Answer(int answer) {}

    private record Fixture(LlmClient client, MockRestServiceServer server) {}

    /** preferred "gemma4" with discovery, a valid json_object reply. */
    private Fixture withModels(String modelsBody) {
        var builder = RestClient.builder().baseUrl("http://platform");
        var server = MockRestServiceServer.bindTo(builder).build();
        var props = new LlmProperties("http://platform", "gemma4", null, null, 0);
        return new Fixture(new LlmClient(builder.build(), props, new JsonMapper()), server);
    }

    @Test
    void validatedJson_dispatchesDiscoveredModel_andParses() {
        var f = withModels(null);
        f.server().expect(requestTo("http://platform/v1/models"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"llama3\"},{\"id\":\"gemma4:e4b\"}]}", APPLICATION_JSON));
        f.server().expect(requestTo("http://platform/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("gemma4:e4b"))                 // "gemma4" preferred -> tag variant picked
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"answer\\\":42}\"}}]}", APPLICATION_JSON));

        assertEquals(42, f.client().chatJson("sys", "the question", Answer.class).answer());
        f.server().verify();
    }

    @Test
    void unparseableJson_isLlmException_soTheFeatureCanFallBack() {
        var f = withModels(null);
        f.server().expect(requestTo("http://platform/v1/models"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"gemma4:e4b\"}]}", APPLICATION_JSON));
        f.server().expect(requestTo("http://platform/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"sorry, no json here\"}}]}", APPLICATION_JSON));

        assertThrows(LlmException.class, () -> f.client().chatJson("sys", "q", Answer.class));
    }

    @Test
    void emptyContent_isLlmException_notSilentlyBlank() {
        var f = withModels(null);
        f.server().expect(requestTo("http://platform/v1/models"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"gemma4:e4b\"}]}", APPLICATION_JSON));
        f.server().expect(requestTo("http://platform/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}", APPLICATION_JSON));

        assertThrows(LlmException.class, () -> f.client().chat("sys", "q"));
    }

    @Test
    void stream_concatenatesContentDeltas_ignoringDoneAndKeepAlives() {
        var f = withModels(null);
        f.server().expect(requestTo("http://platform/v1/models"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"gemma4:e4b\"}]}", APPLICATION_JSON));
        var sse = """
                data: {"choices":[{"delta":{"content":"Refunds"}}]}

                data: {"choices":[{"delta":{"content":" totalled 3"}}]}

                data: [DONE]
                """;
        f.server().expect(requestTo("http://platform/v1/chat/completions"))
                .andExpect(jsonPath("$.stream").value(true))
                .andRespond(withSuccess(sse, TEXT_EVENT_STREAM));

        var out = new StringBuilder();
        f.client().chatStream("sys", "q", out::append);
        assertEquals("Refunds totalled 3", out.toString());
    }
}
