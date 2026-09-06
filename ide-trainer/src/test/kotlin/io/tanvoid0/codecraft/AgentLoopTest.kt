package io.tanvoid0.codecraft

import com.google.gson.JsonObject
import junit.framework.TestCase

/**
 * The tool loop's control flow, driven by a scripted model instead of a real
 * one. This is the part that decides whether a refusal is respected, whether a
 * runaway model is stopped, and whether tool results reach the next round - all
 * of which are wrong-answer-or-worse if they break, and none of which a live
 * Ollama would let us test deterministically.
 */
class AgentLoopTest : TestCase() {

    /** Records what happened, approves everything unless told otherwise. */
    private class Spy(val allow: Boolean = true, val stopAfter: Int = Int.MAX_VALUE) : AgentLoop.Watcher {
        val ran = mutableListOf<Pair<String, String>>()
        var asked = 0
        override fun approve(call: AgentTools.Call): Boolean { asked++; return allow }
        override fun onToolRan(call: AgentTools.Call, output: String) { ran.add(call.name to output) }
        override fun cancelled() = ran.size >= stopAfter
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        AgentTools.Call(name, JsonObject().apply { args.forEach { (k, v) -> addProperty(k, v) } })

    /** Stands in for AgentTools: only commands are gated, every call echoes its name. */
    private fun loop(maxRounds: Int = 8) = AgentLoop(
        schemas = com.google.gson.JsonArray(),
        needsApproval = { it == "run_command" },
        runTool = { "ran ${it.name}" },
        maxRounds = maxRounds,
    )

    fun testAnswersWithoutCallingAnything() {
        val spy = Spy()
        val answer = loop().run(
            listOf("user" to "hello"), "", "",
            round = { _, _ -> Reviewers.Round("hi", emptyList()) },
            watcher = spy,
        )

        assertEquals("hi", answer)
        assertTrue(spy.ran.isEmpty())
    }

    /** A refused command must not run, and the model must be told so. */
    fun testRefusedCallIsNotRun() {
        val spy = Spy(allow = false)
        var round = 0
        val answer = loop().run(
            listOf("user" to "run it"), "", "",
            round = { _, _ ->
                if (round++ == 0) Reviewers.Round("", listOf(call("run_command", "command" to "rm -rf /")))
                else Reviewers.Round("understood", emptyList())
            },
            watcher = spy,
        )

        assertEquals(1, spy.asked)
        assertEquals("understood", answer)
        assertTrue("refusal must reach the model", spy.ran.single().second.contains("refused"))
    }

    /** Reads are not gated - only commands are, or every answer needs a click. */
    fun testReadsAreNotGated() {
        val spy = Spy()
        var round = 0
        loop().run(
            listOf("user" to "read it"), "", "",
            round = { _, _ ->
                if (round++ == 0) Reviewers.Round("", listOf(call("read_file", "path" to "x.kt")))
                else Reviewers.Round("done", emptyList())
            },
            watcher = spy,
        )

        assertEquals("no approval for a read", 0, spy.asked)
        assertEquals(1, spy.ran.size)
    }

    /** A model that only ever calls tools must be stopped, and must say so. */
    fun testRunawayLoopStopsAndAdmitsIt() {
        val spy = Spy()
        val answer = loop(maxRounds = 3).run(
            listOf("user" to "go"), "", "",
            round = { _, _ -> Reviewers.Round("thinking", listOf(call("read_file", "path" to "x.kt"))) },
            watcher = spy,
        )

        assertEquals(3, spy.ran.size)
        assertTrue("must not pass off an unfinished run as an answer", answer.contains("stopped after 3 rounds"))
    }

    /** Cancelling mid-run ends it rather than finishing the remaining rounds. */
    fun testCancelStopsTheLoop() {
        val spy = Spy(stopAfter = 1)
        val answer = loop(maxRounds = 9).run(
            listOf("user" to "go"), "", "",
            round = { _, _ -> Reviewers.Round("partial", listOf(call("read_file", "path" to "x.kt"))) },
            watcher = spy,
        )

        assertEquals(1, spy.ran.size)
        assertTrue(answer.contains("stopped"))
    }

    /** Every tool result has to reach the next round, or the model asks again forever. */
    fun testToolResultsAreFedBack() {
        var seen = 0
        val spy = Spy()
        loop(maxRounds = 3).run(
            listOf("user" to "go"), "", "",
            round = { messages, _ ->
                seen = messages.count { it.asJsonObject.get("role").asString == "tool" }
                if (seen == 0) Reviewers.Round("", listOf(call("read_file", "path" to "x.kt")))
                else Reviewers.Round("done", emptyList())
            },
            watcher = spy,
        )

        assertEquals("the tool result must be in the next round's messages", 1, seen)
    }
}
