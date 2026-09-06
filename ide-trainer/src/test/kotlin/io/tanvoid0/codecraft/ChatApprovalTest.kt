package io.tanvoid0.codecraft

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The gate ADR-002 turns on: "Code (manual)" has to mean the same thing on both
 * backends, with one session allowlist between them. That claim used to live
 * inside a Swing panel, where the only way to check it was to run the IDE and
 * click. Here it is a unit test.
 *
 * Every failure this covers is silent in use: a gate that waves a command
 * through looks exactly like one the user approved, and an allowlist that
 * forgets looks like a model asking twice.
 */
class ChatApprovalTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val posted = mutableListOf<String>()
    private var approveBody = ""

    /** What the panel would have shown, and what it would have been asked. */
    private class Ui(private val answer: (String) -> Int) : ChatUi {
        val asked = mutableListOf<String>()
        val said = mutableListOf<Pair<String, String>>()
        override fun say(who: String, text: String) { said.add(who to text) }
        override fun beginReply(who: String) = Unit
        override fun token(piece: String) = Unit
        override fun endReply() = Unit
        override fun askToRun(command: String): Int {
            asked.add(command)
            return answer(command)
        }
    }

    private fun controller(ui: Ui) = ChatController(project, Ide(project), ui, db = null)

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/coder/chat/approve/send") { ex ->
            posted.add(String(ex.requestBody.readBytes(), StandardCharsets.UTF_8))
            reply(ex, approveBody)
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    override fun tearDown() {
        try {
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private fun reply(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun parked(command: String? = "cargo test") = Reviewers.AgentTurn(
        text = "",
        threadId = 7,
        pending = Reviewers.PendingCall(callId = "c1", name = "run_command", command = command),
    )

    // ---- the gate ----------------------------------------------------------

    fun testAutoModeRunsWithoutAsking() {
        val ui = Ui { fail("auto mode must not prompt"); -1 }

        assertTrue(controller(ui).approve(Mode.CODE_AUTO, "rm -rf build", "run_command"))
        assertTrue(ui.asked.isEmpty())
    }

    fun testManualModeAsksAndRunOnceDoesNotStick() {
        val ui = Ui { 0 }   // Run Once
        val controller = controller(ui)

        assertTrue(controller.approve(Mode.CODE_MANUAL, "ls", "run_command"))
        assertTrue(controller.approve(Mode.CODE_MANUAL, "ls", "run_command"))

        assertEquals("run once means once, so it asks again", listOf("ls", "ls"), ui.asked)
    }

    fun testAlwaysAllowIsRememberedForTheSession() {
        val ui = Ui { 1 }   // Always Allow
        val controller = controller(ui)

        assertTrue(controller.approve(Mode.CODE_MANUAL, "./mvnw test", "run_command"))
        assertTrue(controller.approve(Mode.CODE_MANUAL, "./mvnw test", "run_command"))

        assertEquals(listOf("./mvnw test"), ui.asked)
    }

    /** An allowlist is per command, not a blanket yes. */
    fun testAllowingOneCommandDoesNotAllowAnother() {
        val ui = Ui { if (it == "ls") 1 else 2 }
        val controller = controller(ui)

        assertTrue(controller.approve(Mode.CODE_MANUAL, "ls", "run_command"))
        assertFalse(controller.approve(Mode.CODE_MANUAL, "rm -rf /", "run_command"))
    }

    fun testDenyRefuses() {
        assertFalse(controller(Ui { 2 }).approve(Mode.CODE_MANUAL, "ls", "run_command"))
    }

    /** Closing the dialog returns -1, and dismissing a permission prompt is not consent. */
    fun testDismissingTheDialogCountsAsDeny() {
        assertFalse(controller(Ui { -1 }).approve(Mode.CODE_MANUAL, "ls", "run_command"))
    }

    /**
     * A call the server gates but this client cannot describe still gets asked
     * about, by name, rather than waved through for having no command to show.
     */
    fun testACallWithNoCommandIsAskedAboutByName() {
        val ui = Ui { 2 }

        assertFalse(controller(ui).approve(Mode.CODE_MANUAL, null, "write_file"))
        assertEquals(listOf("write_file"), ui.asked)
    }

    // ---- carrying a parked turn through --------------------------------

    fun testAnApprovedCallIsPostedAndTheAnswerComesBack() {
        approveBody = """{"thread_id":7,"messages":[{"role":"assistant","content":"tests passed"}]}"""
        val ui = Ui { 0 }

        val answer = controller(ui).resolvePending(parked(), Mode.CODE_MANUAL, base, null, "")

        assertEquals("tests passed", answer)
        assertTrue(posted.single(), posted.single().contains("\"approve\":true"))
        assertTrue(posted.single(), posted.single().contains("\"call_id\":\"c1\""))
    }

    /**
     * A refusal is *sent*, not dropped: the server records it as the tool result
     * and tells the model, which is what stops it retrying the same command.
     */
    fun testARefusalIsPostedRatherThanDropped() {
        approveBody = """{"thread_id":7,"messages":[{"role":"assistant","content":"understood"}]}"""

        val answer = controller(Ui { 2 }).resolvePending(parked(), Mode.CODE_MANUAL, base, null, "")

        assertEquals("understood", answer)
        assertTrue(posted.single(), posted.single().contains("\"approve\":false"))
    }

    /** Auto mode resolves a parked call without a prompt, same as the local loop. */
    fun testAutoModeResolvesAParkedCallSilently() {
        approveBody = """{"thread_id":7,"messages":[{"role":"assistant","content":"done"}]}"""
        val ui = Ui { fail("auto mode must not prompt"); -1 }

        assertEquals("done", controller(ui).resolvePending(parked(), Mode.CODE_AUTO, base, null, ""))
        assertTrue(posted.single().contains("\"approve\":true"))
    }

    /** An already-answered turn needs no approval at all. */
    fun testATurnThatIsNotParkedIsReturnedAsIs() {
        val ui = Ui { fail("nothing is pending"); -1 }
        val finished = Reviewers.AgentTurn("all done", 7, null)

        assertEquals("all done", controller(ui).resolvePending(finished, Mode.CODE_MANUAL, base, null, ""))
        assertTrue("nothing to post", posted.isEmpty())
    }

    /** Each parked call is announced, so the transcript shows what was run and what was refused. */
    fun testTheTranscriptRecordsWhatWasApproved() {
        approveBody = """{"thread_id":7,"messages":[{"role":"assistant","content":"ok"}]}"""
        val ui = Ui { 2 }

        controller(ui).resolvePending(parked(), Mode.CODE_MANUAL, base, null, "")

        assertEquals("· run_command" to "(refused)", ui.said.single())
    }
}
