package io.tanvoid0.codecraft

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The tool loop, running in the plugin: ask the model, run what it asks for,
 * hand back the results, ask again - until it answers instead of calling, or
 * hits a ceiling.
 *
 * This is what makes the chat an agent rather than a text box, and it is
 * deliberately *local*. agent-platform runs the same shape of loop on its own
 * server, which is why nothing here could gate it (PROGRESS.md); with the loop
 * in-process there is a line between every call and the project, which is what
 * [Gate] stands on.
 *
 * Blocking - it is driven from a pooled thread, and every callback it makes is
 * documented as arriving on that thread, not the EDT.
 */
class AgentLoop(
    private val schemas: JsonArray,
    private val needsApproval: (String) -> Boolean,
    private val runTool: (AgentTools.Call) -> String,
    private val maxRounds: Int = 8,
) {

    /** What the caller learns as the loop runs, all of it off the EDT. */
    interface Watcher {
        /** A call is about to run. Return false to refuse it; the model is told it was refused. */
        fun approve(call: AgentTools.Call): Boolean

        /** A call ran. `output` is exactly what the model will be shown. */
        fun onToolRan(call: AgentTools.Call, output: String)

        /** True once the user has asked to stop; checked between rounds. */
        fun cancelled(): Boolean
    }

    /**
     * Runs to an answer. `history` is the conversation so far as (role, text);
     * the returned text is the model's final prose.
     *
     * Ends on: the model answering without calls, [maxRounds] exhausted, or the
     * watcher cancelling. Hitting the ceiling is reported in the answer rather
     * than silently truncated - a loop that quietly gave up looks like a wrong
     * answer instead of an unfinished one.
     */
    fun run(
        history: List<Pair<String, String>>,
        endpoint: String,
        model: String,
        round: (JsonArray, JsonArray) -> Reviewers.Round = { m, t -> Reviewers.ollamaRound(m, t, endpoint, model) },
        watcher: Watcher,
    ): String {
        val messages = JsonArray().apply {
            history.forEach { (role, content) ->
                add(JsonObject().apply { addProperty("role", role); addProperty("content", content) })
            }
        }
        var lastContent = ""

        repeat(maxRounds) {
            if (watcher.cancelled()) return stopped(lastContent)
            val result = round(messages, schemas)
            lastContent = result.content.ifBlank { lastContent }
            if (result.calls.isEmpty()) return result.content

            // The assistant's own turn has to go back in before its results do,
            // or the next round sees tool output answering nothing.
            messages.add(JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", result.content)
            })
            for (call in result.calls) {
                if (watcher.cancelled()) return stopped(lastContent)
                val output = when {
                    needsApproval(call.name) && !watcher.approve(call) ->
                        "The user refused this. Do not try it again; explain what you needed it for."
                    else -> runTool(call)
                }
                watcher.onToolRan(call, output)
                messages.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("content", "${call.name}: $output")
                })
            }
        }
        return (lastContent.ifBlank { "" } +
            "\n\n[stopped after $maxRounds rounds of tool calls without reaching an answer]").trim()
    }

    private fun stopped(soFar: String) =
        (soFar.ifBlank { "" } + "\n\n[stopped]").trim()
}
