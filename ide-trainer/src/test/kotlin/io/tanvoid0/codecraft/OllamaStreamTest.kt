package io.tanvoid0.codecraft

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Streaming, against the same kind of JDK stub server the rest of Reviewers is
 * tested with. Worth its own file because every failure here is silent: tokens
 * arriving mangled, an abort that does not abort, or a lost token count all
 * look like a working chat that is subtly wrong.
 */
class OllamaStreamTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private var ndjson: String = ""

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            exchange.requestBody.readBytes()
            val bytes = ndjson.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/x-ndjson")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun line(content: String) = """{"message":{"content":"$content"},"done":false}"""

    private fun stream(abort: () -> Boolean = { false }, onToken: (String) -> Unit = {}) =
        Reviewers.ollamaStream(listOf("user" to "hi"), base, "m", abort, onToken)

    /**
     * The one that bites: a fragment is very often a single space, and the
     * codebase's own `str()` helper drops blank strings. Losing those runs
     * every word of the answer together.
     */
    @Test fun spacesBetweenTokensSurvive() {
        ndjson = listOf(line("Hello"), line(" "), line("world"), """{"message":{"content":""},"done":true}""")
            .joinToString("\n")

        assertEquals("Hello world", stream().first)
    }

    @Test fun tokensArriveOneAtATime() {
        ndjson = listOf(line("a"), line("b"), """{"message":{"content":"c"},"done":true}""").joinToString("\n")
        val seen = mutableListOf<String>()

        val (whole, _) = stream(onToken = { seen.add(it) })

        assertEquals(listOf("a", "b", "c"), seen)
        assertEquals("abc", whole)
    }

    /** A malformed chunk must not throw away an answer that is otherwise fine. */
    @Test fun aBadLineIsSkippedNotFatal() {
        ndjson = listOf(line("ok"), "{not json at all", """{"message":{"content":"!"},"done":true}""")
            .joinToString("\n")

        assertEquals("ok!", stream().first)
    }

    /** Stop must end the read, and must keep what already arrived. */
    @Test fun abortStopsAndKeepsWhatArrived() {
        ndjson = (1..50).joinToString("\n") { line("x") }
        var seen = 0

        val (whole, _) = stream(abort = { seen >= 3 }, onToken = { seen++ })

        assertTrue("abort must stop well short of 50, got $seen", seen in 1..5)
        assertEquals("x".repeat(seen), whole)
    }

    /** Token counts ride on the final line; streaming must not lose them. */
    @Test fun usageComesOffTheFinalLine() {
        ndjson = listOf(
            line("hi"),
            """{"message":{"content":""},"done":true,"prompt_eval_count":11,"eval_count":4}""",
        ).joinToString("\n")

        val (_, usage) = stream()

        assertEquals(11, usage.promptTokens)
        assertEquals(4, usage.completionTokens)
    }
}
