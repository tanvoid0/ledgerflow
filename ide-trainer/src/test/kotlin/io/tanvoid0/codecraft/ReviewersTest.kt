package io.tanvoid0.codecraft

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A reviewer that answers over HTTP either works against a server or is never
 * tested at all — and the agent-platform one has the only loop in the plugin
 * that can spin forever. The JDK ships an HTTP server, so the stub costs
 * nothing: no dependency, no mocking framework, no network.
 */
class ReviewersTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** Bodies the stub sends back, in order, per path. */
    private val replies = HashMap<String, MutableList<Pair<Int, String>>>()
    private val seen = ArrayList<String>()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun respond(exchange: HttpExchange) {
        seen.add("${exchange.requestMethod} ${exchange.requestURI.path}")
        exchange.requestBody.readBytes()
        val queued = replies[exchange.requestURI.path]
        val (code, body) = when {
            queued == null || queued.isEmpty() -> 404 to """{"error":"nothing queued"}"""
            queued.size == 1 -> queued.first()          // the last reply repeats
            else -> queued.removeAt(0)
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun queue(path: String, body: String, code: Int = 200) {
        replies.getOrPut(path) { ArrayList() }.add(code to body)
    }

    // ---- the local model -------------------------------------------------

    @Test fun ollamaReturnsTheModelsAnswer() {
        queue("/api/generate", """{"response":"looks fine"}""")

        assertEquals("looks fine", Reviewers.ollama("class A {}", "$base/api/generate", "qwen2.5-coder"))
        assertEquals(listOf("POST /api/generate"), seen)
    }

    /** Placement goes through the same call, and comes back as line numbers. */
    @Test fun locateTurnsTheModelsAnswerIntoAnEdit() {
        queue("/api/generate", """{"response":"{\"start\": 2, \"end\": 2, \"indent\": \"  \"}"}""")

        val edit = Reviewers.locate("pom.xml", "a\nb\nc", "x", "$base/api/generate", "qwen2.5-coder")

        assertEquals(2, edit.start)
        assertEquals(0, edit.replaces)
        assertEquals("  ", edit.indent)
    }

    // ---- the model list behind the dropdown --------------------------------

    @Test fun modelsComeBackWithTheMetadataTheServerGives() {
        queue(
            "/api/tags",
            """{"models":[
                 {"name":"qwen2.5-coder:7b","size":4683087519,"modified_at":"2025-01-04T10:00:00Z",
                  "details":{"family":"qwen2","parameter_size":"7.6B","quantization_level":"Q4_K_M"}},
                 {"name":"llama3.2:1b","details":{"parameter_size":"1.2B"}}
               ]}""",
        )

        val found = Reviewers.models("$base/api/generate")

        assertEquals(listOf("llama3.2:1b", "qwen2.5-coder:7b"), found.map { it.name })
        assertEquals("7.6B · Q4_K_M · 4.7 GB · qwen2 · 2025-01-04", found[1].meta)
        // The catalogue lives next to the endpoint the reviewer posts to.
        assertEquals(listOf("GET /api/tags"), seen)
    }

    @Test fun aModelWithNoDetailsIsStillListed() {
        queue("/api/tags", """{"models":[{"name":"bare"}]}""")

        assertEquals(listOf(ModelInfo("bare", emptyList())), Reviewers.models("$base/api/generate"))
    }

    @Test fun theCatalogueUrlIsDerivedFromWhateverEndpointIsConfigured() {
        assertEquals("http://h:11434/api/tags", Reviewers.tagsUrl("http://h:11434/api/generate"))
        assertEquals("http://h:11434/api/tags", Reviewers.tagsUrl("http://h:11434/"))
        assertEquals("http://h:11434/api/tags", Reviewers.tagsUrl("http://h:11434/api/chat"))
    }

    /** A dead server must fail here, in the settings page, with the reason. */
    @Test fun listingModelsFailsLoudlyWhenNothingIsServing() {
        queue("/api/tags", "nope", code = 500)

        assertTrue(
            runCatching { Reviewers.models("$base/api/generate") }
                .exceptionOrNull()?.message?.contains("500") == true
        )
    }

    // ---- the agent platform's own catalogue ---------------------------------

    @Test fun agentAliveCountsTheTeamTemplates() {
        queue("/api/v1/teams/", """{"teams":[{"id":1},{"id":2}]}""")

        assertEquals("Reachable - 2 team templates", Reviewers.agentAlive(base, null))
    }

    /** Three envelopes exist in the wild; the first path that answers wins. */
    @Test fun agentModelsReadsWhicheverShapeTheServerUses() {
        queue("/api/v1/models", """{"data":[{"id":"claude-opus","owned_by":"anthropic","context_length":204800}]}""")

        val found = Reviewers.agentModels(base)

        assertEquals(listOf("claude-opus"), found.map { it.name })
        assertEquals("anthropic · 200k ctx", found.single().meta)
    }

    /** A platform that publishes no catalogue is a fact to show, not a failure. */
    @Test fun agentModelsIsEmptyWhenNoCandidatePathAnswers() {
        assertEquals(emptyList<ModelInfo>(), Reviewers.agentModels(base))
    }

    /** A model that is not there must say so, not return an empty review. */
    @Test fun ollamaFailsLoudlyOnAnErrorStatus() {
        queue("/api/generate", """{"error":"no such model"}""", code = 500)

        val e = runCatching { Reviewers.ollama("code", "$base/api/generate", "nope") }.exceptionOrNull()

        assertTrue("expected the status in the message, got: ${e?.message}", e?.message?.contains("500") == true)
    }

    @Test fun ollamaFailsWhenThereIsNoResponseField() {
        queue("/api/generate", """{"done":true}""")

        assertTrue(
            runCatching { Reviewers.ollama("code", "$base/api/generate", "m") }
                .exceptionOrNull()?.message?.contains("No response field") == true
        )
    }

    // ---- the agent team --------------------------------------------------

    private fun agentTeam() = Reviewers.agentTeam(
        code = "class A {}", baseUrl = base, teamId = 7, pollMillis = 10, deadlineMillis = 5_000
    )

    /** The point of the loop: keep asking while the team is still working. */
    @Test fun agentTeamPollsUntilTheProcessFinishes() {
        queue("/api/v1/processes", """{"process_id":42}""")
        queue("/api/v1/processes/42", """{"process":{"status":"running"},"tasks":[]}""")
        queue("/api/v1/processes/42", """{"process":{"status":"running"},"tasks":[]}""")
        queue(
            "/api/v1/processes/42",
            """{"process":{"status":"completed"},
                "tasks":[{"role":"reviewer","output":"off-by-one on line 3"},
                         {"role":"tester","output":null}]}"""
        )

        val review = agentTeam()

        assertEquals("reviewer: off-by-one on line 3\n\ntester: (no output)", review)
        assertEquals(
            listOf(
                "POST /api/v1/processes",
                "GET /api/v1/processes/42",
                "GET /api/v1/processes/42",
                "GET /api/v1/processes/42",
            ),
            seen
        )
    }

    /** A failed run reports the platform's own reason, not a generic error. */
    @Test fun agentTeamReportsTheFailureReason() {
        queue("/api/v1/processes", """{"process_id":9}""")
        queue("/api/v1/processes/9", """{"process":{"status":"failed","failure_reason":"no agent free"},"tasks":[]}""")

        val e = runCatching { agentTeam() }.exceptionOrNull() ?: fail("should have thrown")

        assertEquals("no agent free", (e as Throwable).message)
    }

    /** Cancelled is terminal too, and has no reason to report. */
    @Test fun agentTeamHandlesCancellation() {
        queue("/api/v1/processes", """{"process_id":9}""")
        queue("/api/v1/processes/9", """{"process":{"status":"cancelled"},"tasks":[]}""")

        assertEquals("Process cancelled", runCatching { agentTeam() }.exceptionOrNull()?.message)
    }

    /** A team that never finishes must give up, or the pooled thread never comes back. */
    @Test fun agentTeamGivesUpAtTheDeadline() {
        queue("/api/v1/processes", """{"process_id":1}""")
        queue("/api/v1/processes/1", """{"process":{"status":"running"},"tasks":[]}""")

        val e = runCatching {
            Reviewers.agentTeam("code", base, teamId = 1, pollMillis = 5, deadlineMillis = 100)
        }.exceptionOrNull()

        assertTrue("expected a timeout, got: ${e?.message}", e?.message?.contains("Timed out") == true)
    }

    @Test fun agentTeamSendsTheTeamIdAndAuthorisesWhenGivenAToken() {
        server.removeContext("/")
        var auth: String? = null
        var body: String? = null
        server.createContext("/") { exchange ->
            auth = exchange.requestHeaders.getFirst("Authorization")
            body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8).ifEmpty { body }
            val reply = """{"process_id":1,"process":{"status":"completed"},"tasks":[]}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }

        Reviewers.agentTeam("code", base, teamId = 7, token = "s3cret", pollMillis = 5)

        assertEquals("Bearer s3cret", auth)
        assertTrue("team id should be in the request: $body", body?.contains("\"team_template_id\":7") == true)
    }
}
