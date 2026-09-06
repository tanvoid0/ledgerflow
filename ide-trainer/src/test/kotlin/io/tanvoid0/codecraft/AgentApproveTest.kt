package io.tanvoid0.codecraft

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Reading a Coder turn that stopped to ask, and answering it.
 *
 * This is the half that makes "Code (manual)" real on the Agent Platform
 * backend: the server parks on a call and does nothing more until
 * `/coder/chat/approve/send` resolves it. Both failures here are silent —
 * a `pending_call` read wrong looks like an empty answer, and an approval
 * posted wrong looks like a thread that simply stopped talking.
 */
class AgentApproveTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val seen = mutableListOf<String>()
    private var sendBody = ""
    private var approveBody = ""

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/coder/chat/send") { exchange ->
            seen.add("send:" + String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8))
            reply(exchange, sendBody)
        }
        server.createContext("/api/v1/coder/chat/approve/send") { exchange ->
            seen.add("approve:" + String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8))
            reply(exchange, approveBody)
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun reply(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun answered(text: String) =
        """{"thread_id":7,"messages":[{"role":"assistant","content":"$text"}]}"""

    private val parked =
        """{"thread_id":7,"pending_call":{"call_id":"c1","name":"run_command",
           "arguments":{"command":"cargo test"}},"messages":[]}""".trimIndent()

    private fun send() = Reviewers.agentChat("hi", null, base, null)

    /** A finished turn: the answer, the thread to continue on, nothing pending. */
    @Test fun anAnsweredTurnCarriesNoPendingCall() {
        sendBody = answered("all done")

        val turn = send()

        assertEquals("all done", turn.text)
        assertEquals(7, turn.threadId)
        assertNull(turn.pending)
    }

    /**
     * The parked case. Asking a parked payload for its answer would throw on
     * the empty `messages` array, which is why the pending check comes first.
     */
    @Test fun aParkedTurnReportsTheCallItWaitsOn() {
        sendBody = parked

        val pending = send().pending

        assertEquals("c1", pending?.callId)
        assertEquals("run_command", pending?.name)
        assertEquals("the command is what the user is shown", "cargo test", pending?.command)
    }

    /** Content blocks, not a plain string — the other shape seen in the wild. */
    @Test fun contentBlocksAreJoinedIntoTheAnswer() {
        sendBody = """{"thread_id":7,"messages":[{"role":"assistant",
            "content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}]}""".trimIndent()

        assertEquals("one\ntwo", send().text)
    }

    /** A call the server gates but this panel cannot describe still parses. */
    @Test fun aPendingCallWithNoCommandIsStillReadable() {
        sendBody = """{"thread_id":7,"pending_call":{"call_id":"c2","name":"write_file",
            "arguments":{"path":"a.txt"}},"messages":[]}""".trimIndent()

        val pending = send().pending

        assertEquals("write_file", pending?.name)
        assertNull("nothing to quote in the prompt", pending?.command)
    }

    /** The verdict has to reach the server, or the thread waits forever. */
    @Test fun approvalPostsTheVerdictAndReturnsTheResumedTurn() {
        approveBody = answered("tests passed")

        val turn = Reviewers.agentApprove(7, "c1", approve = true, baseUrl = base, token = null)

        val posted = seen.single { it.startsWith("approve:") }
        assertTrue(posted, posted.contains("\"call_id\":\"c1\""))
        assertTrue(posted, posted.contains("\"approve\":true"))
        assertTrue(posted, posted.contains("\"thread_id\":7"))
        assertEquals("tests passed", turn.text)
    }

    /**
     * A refusal is *sent*, not dropped: the server records it as the tool
     * result and tells the model, which is what stops it retrying the same
     * command forever.
     */
    @Test fun aRefusalIsPostedToo() {
        approveBody = answered("understood")

        Reviewers.agentApprove(7, "c1", approve = false, baseUrl = base, token = null)

        assertTrue(seen.single { it.startsWith("approve:") }.contains("\"approve\":false"))
    }
}
