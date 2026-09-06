package io.tanvoid0.codecraft

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The two code reviewers behind the panel's **Review** and **Deep Review**
 * links, as plain functions over HTTP: no IDE types, no threading, no
 * notifications — so a stub server is enough to test them, which is the only
 * way the polling loop was ever going to be checked.
 *
 * [Ide] is what puts them on a pooled thread and turns the answer into a
 * balloon.
 */
/**
 * A model a server offers, and whatever that server says about it — size,
 * quantisation, family, when it was pulled. The details are the reason the
 * dropdown is worth having: names alone do not tell you that one of them is
 * 1.5B and hopeless at placing a snippet.
 */
data class ModelInfo(val name: String, val details: List<String>) {
    val meta: String get() = details.joinToString(" · ")

    /** The name alone: this is what an editable combo puts in its text field. */
    override fun toString() = name

    /** What the list shows — the metadata is the point of the dropdown. */
    fun label() = if (details.isEmpty()) name else "$name  —  $meta"
}

object Reviewers {

    private const val PROMPT =
        "Review this code for bugs. Be concise, list issues only, say \"looks fine\" if none."

    private val gson = Gson()

    private fun client() = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** One local model, one answer. Throws with the reason if it does not come. */
    fun ollama(code: String, endpoint: String, model: String): String =
        ask("$PROMPT\n\n$code", endpoint, model)

    /**
     * Where a `paste` block belongs in the file it is pasted into, decided by
     * the same local model. Ollama's `format: json` constrains the decoding,
     * which is cheaper than teaching every model to stop wrapping its answer
     * in prose.
     *
     * Only line numbers come back from the model; [Patch] writes the snippet.
     */
    fun locate(path: String, file: String, snippet: String, endpoint: String, model: String): Patch.Edit =
        Patch.parse(
            ask(Patch.prompt(path, file, snippet), endpoint, model, json = true),
            file.lines().size,
            snippet.trimEnd().lines().size,
        )

    /**
     * The models that server actually has, which is both the list behind the
     * settings dropdown and the honest answer to "is it running": a machine
     * with no Ollama on it fails here, with the reason, in a settings page
     * rather than three clicks into a step.
     *
     * The metadata comes back with the list and is the part that decides
     * anything — a 1.5B model and a 32B one have the same kind of name and
     * completely different odds of putting a snippet in the right place.
     */
    fun models(endpoint: String): List<ModelInfo> {
        val request = HttpRequest.newBuilder(URI.create(tagsUrl(endpoint)))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = client().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Ollama returned ${response.statusCode()} for /api/tags" }
        val models = gson.fromJson(response.body(), JsonObject::class.java)?.getAsJsonArray("models")
            ?: error("No models field from Ollama")
        return models.map { it.asJsonObject }.mapNotNull { m ->
            val name = m.str("name") ?: m.str("model") ?: return@mapNotNull null
            val d = m.getAsJsonObject("details")
            ModelInfo(
                name,
                listOfNotNull(
                    d?.str("parameter_size"),
                    d?.str("quantization_level"),
                    m.get("size")?.takeIf { it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull()?.let(::gb),
                    d?.str("family"),
                    m.str("modified_at")?.take(10),
                ),
            )
        }.sortedBy { it.name }
    }

    /**
     * The same question asked of the agent platform. It has no documented
     * catalogue endpoint this plugin can rely on, so the candidates are tried
     * in order and the first that answers wins — an empty list means "that
     * server does not publish one", which is a fact worth showing rather than
     * an error worth throwing.
     */
    fun agentModels(baseUrl: String, token: String? = null): List<ModelInfo> {
        val root = baseUrl.trim().trimEnd('/')
        for (path in listOf("/api/v1/models", "/api/v1/models/", "/v1/models")) {
            val found = runCatching { parseModels(getJson(root + path, token)) }.getOrNull()
            if (found != null) return found
        }
        return emptyList()
    }

    /**
     * Whoever wrote the server chose the envelope, so accept the three that
     * exist in the wild and read metadata from whatever keys are present
     * rather than insisting on a shape.
     */
    private fun parseModels(body: String): List<ModelInfo> {
        val root = gson.fromJson(body, com.google.gson.JsonElement::class.java) ?: error("no json")
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("data")
                ?: root.asJsonObject.getAsJsonArray("models")
                ?: error("no model array")
            else -> error("no model array")
        }
        return array.mapNotNull { el ->
            if (el.isJsonPrimitive) return@mapNotNull ModelInfo(el.asString, emptyList())
            val o = el.asJsonObject
            val name = o.str("id") ?: o.str("name") ?: o.str("model") ?: return@mapNotNull null
            ModelInfo(
                name,
                listOfNotNull(
                    o.str("provider") ?: o.str("owned_by"),
                    o.str("parameter_size") ?: o.str("size"),
                    o.get("context_length")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()
                        ?.let { "${it / 1024}k ctx" },
                    o.str("description")?.take(60),
                ),
            )
        }.sortedBy { it.name }
    }

    private fun getJson(url: String, token: String?): String {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        val response = client().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "$url returned ${response.statusCode()}" }
        return response.body()
    }

    private fun JsonObject.str(key: String) =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String) =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_000_000_000.0)

    /**
     * The catalogue sits next to whatever the reviewer posts to: the setting
     * is a full endpoint (`.../api/generate`), not a base URL, because that is
     * what Ollama's own docs give people to paste.
     */
    internal fun tagsUrl(endpoint: String): String {
        val e = endpoint.trim().trimEnd('/')
        return (if ("/api/" in e) e.substringBefore("/api/") else e) + "/api/tags"
    }

    /**
     * Whether the agent platform is up, said in one line. Its team list is the
     * cheapest authenticated GET it has, so this checks the token as well as
     * the port, and counts the templates the team id has to come from.
     */
    fun agentAlive(baseUrl: String, token: String?): String {
        val builder = HttpRequest.newBuilder(URI.create("${baseUrl.trim().trimEnd('/')}/api/v1/teams/"))
            .timeout(Duration.ofSeconds(10))
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        val response = client().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Teams returned ${response.statusCode()}: ${response.body()}" }
        // Shape is that platform's business and has changed before; the count
        // is a nicety, being reachable at all is the answer.
        val teams = runCatching {
            val root = gson.fromJson(response.body(), com.google.gson.JsonElement::class.java)
            when {
                root.isJsonArray -> root.asJsonArray.size()
                root.isJsonObject -> root.asJsonObject.getAsJsonArray("teams")?.size()
                else -> null
            }
        }.getOrNull()
        return if (teams != null) "Reachable - $teams team templates" else "Reachable"
    }

    private fun ask(prompt: String, endpoint: String, model: String, json: Boolean = false): String {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt)
            addProperty("stream", false)
            if (json) addProperty("format", "json")
        }
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        val response = client().send(request, HttpResponse.BodyHandlers.ofString())
        // The body on a non-200 is Ollama's own reason - "model \"x\" not
        // found, try pulling it first" is the difference between a dead
        // server and a model that just needs `ollama pull`.
        check(response.statusCode() == 200) { "Ollama returned ${response.statusCode()}: ${response.body()}" }
        return gson.fromJson(response.body(), JsonObject::class.java)
            ?.get("response")?.takeIf { !it.isJsonNull }?.asString
            ?: error("No response field from Ollama")
    }

    /** `.../api/generate` -> `.../api/chat`: same host as the review setting, chat's own verb. */
    private fun ollamaChatUrl(endpoint: String): String {
        val e = endpoint.trim().trimEnd('/')
        return (if ("/api/" in e) e.substringBefore("/api/") else e) + "/api/chat"
    }

    /**
     * Multi-turn Ollama chat. `/api/generate` (what [ollama] and [locate] use)
     * takes one prompt; this takes the whole conversation each time, which is
     * what a chat panel actually has - Ollama itself has no server-side
     * session to hand a thread id for.
     */
    /**
     * What one round of the tool loop came back with: either an answer, or a
     * list of calls the model wants made first. Ollama can return both - text
     * alongside calls - so this keeps whatever content came with them.
     */
    data class Round(val content: String, val calls: List<AgentTools.Call>)

    /**
     * One round of Ollama chat *with tools offered*. Unlike [ollamaChat] this
     * takes messages already shaped (so tool results can be threaded back in as
     * `role: "tool"`) and hands back any `tool_calls` rather than only prose.
     *
     * A model without function-calling support simply never returns calls, and
     * the loop then behaves exactly like the plain chat it replaced.
     */
    /**
     * Streaming chat: Ollama answers `/api/chat` with `stream: true` as NDJSON,
     * one JSON object per line, each carrying the next fragment of content.
     * `onToken` is called with each fragment as it lands, and the whole reply is
     * returned at the end.
     *
     * Cancellation is why this reads the body as a stream rather than a string.
     * `abort` is polled between lines, and returning true closes the connection
     * mid-flight - a blocking `BodyHandlers.ofString()` could only be abandoned,
     * leaving the model generating into a socket nobody reads. What was received
     * before the stop is still returned, because a half answer beats none.
     *
     * Tool calls do not stream usefully (they arrive whole, in one line), so
     * this is for the plain-chat path; [ollamaRound] stays non-streaming.
     */
    fun ollamaStream(
        history: List<Pair<String, String>>,
        endpoint: String,
        model: String,
        abort: () -> Boolean,
        onToken: (String) -> Unit,
    ): Pair<String, ChatUsage> {
        val messages = JsonArray().apply {
            history.forEach { (role, content) ->
                add(JsonObject().apply { addProperty("role", role); addProperty("content", content) })
            }
        }
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("stream", true)
        }
        val request = HttpRequest.newBuilder(URI.create(ollamaChatUrl(endpoint)))
            .timeout(Duration.ofSeconds(600))   // the read is incremental; only a stalled socket should time out
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        val response = client().send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "Ollama returned ${response.statusCode()}" }
        val whole = StringBuilder()
        var usage = ChatUsage(null, null)
        response.body().bufferedReader().use { reader ->
            while (true) {
                if (abort()) break                       // closing the stream is what actually stops generation
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                // A malformed line is skipped rather than thrown: one bad chunk
                // must not discard an answer that is otherwise arriving fine.
                val obj = runCatching { gson.fromJson(line, JsonObject::class.java) }.getOrNull() ?: continue
                // Deliberately not `str()`: that helper drops blank strings, and
                // a streamed fragment is very often a single space. Losing those
                // would run every word of the answer together.
                obj.getAsJsonObject("message")
                    ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { piece ->
                        whole.append(piece)
                        onToken(piece)
                    }
                if (obj.get("done")?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() == true) {
                    // Counts ride on the final line, so streaming keeps the
                    // token readout the non-streaming path already showed.
                    usage = ChatUsage(obj.int("prompt_eval_count"), obj.int("eval_count"))
                    break
                }
            }
        }
        return whole.toString() to usage
    }

    fun ollamaRound(messages: JsonArray, tools: JsonArray, endpoint: String, model: String): Round {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            if (tools.size() > 0) add("tools", tools)
            addProperty("stream", false)
        }
        val request = HttpRequest.newBuilder(URI.create(ollamaChatUrl(endpoint)))
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        val response = client().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Ollama returned ${response.statusCode()}: ${response.body()}" }
        val message = gson.fromJson(response.body(), JsonObject::class.java)?.getAsJsonObject("message")
            ?: error("No message field from Ollama")
        val calls = message.getAsJsonArray("tool_calls").orEmpty().mapNotNull { element ->
            val fn = element.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("function") ?: return@mapNotNull null
            val name = fn.str("name") ?: return@mapNotNull null
            // Arguments arrive as an object from Ollama, but some builds send
            // the JSON as a string - accept either rather than dropping a call.
            val raw = fn.get("arguments")
            val args = when {
                raw == null -> JsonObject()
                raw.isJsonObject -> raw.asJsonObject
                raw.isJsonPrimitive -> runCatching { gson.fromJson(raw.asString, JsonObject::class.java) }.getOrNull() ?: JsonObject()
                else -> JsonObject()
            }
            AgentTools.Call(name, args)
        }
        return Round(message.str("content").orEmpty(), calls)
    }

    /**
     * Ollama's own count, straight off the response - not an estimate. Either
     * field can be absent (older servers, or a model that doesn't report it),
     * so both are nullable rather than defaulting to 0 and lying about it.
     */
    data class ChatUsage(val promptTokens: Int?, val completionTokens: Int?)

    fun ollamaChat(history: List<Pair<String, String>>, endpoint: String, model: String): Pair<String, ChatUsage> {
        val messages = com.google.gson.JsonArray().apply {
            history.forEach { (role, content) -> add(JsonObject().apply { addProperty("role", role); addProperty("content", content) }) }
        }
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("stream", false)
        }
        val request = HttpRequest.newBuilder(URI.create(ollamaChatUrl(endpoint)))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        val response = client().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Ollama returned ${response.statusCode()}: ${response.body()}" }
        val root = gson.fromJson(response.body(), JsonObject::class.java) ?: error("No JSON from Ollama")
        val message = root.getAsJsonObject("message") ?: error("No message field from Ollama")
        val content = message.str("content") ?: error("No content in Ollama's reply")
        return content to ChatUsage(root.int("prompt_eval_count"), root.int("eval_count"))
    }

    /**
     * One turn of agent-platform's Coder agent (`/api/v1/coder/chat/send`) -
     * the one piece of that platform built for exactly this: a conversational,
     * repo-aware assistant that loops over its own tools (read_file,
     * write_file, list_dir, search, repo_map, run_command) inside
     * `run_agent_turn` *server-side* until it has a final answer - this call
     * is one HTTP round trip, not one per tool step. `thread_id` null starts
     * a new thread; the id it returns carries the next turn. Unaffected by
     * the broken `teams` insert - this route has nothing to do with team
     * templates.
     *
     * `allowCommands` is the one gate this client exposes: whether the loop
     * may reach for `run_command` (a real shell) at all. Off by default -
     * reading/writing curriculum files is the point of a mentor chat, running
     * arbitrary shell from it is not something to default to. `autoApprove`
     * hands the server leave to approve its own gated calls, which is the
     * chat panel's "Code (auto)" mode and nothing weaker: with no SSE approve
     * client here, the alternative is not a prompt, it is a dead end.
     *
     * A tool call the server itself gates further (its `pending_call`) comes
     * back rather than looping forever waiting for an approval this client
     * does not implement - the desktop app is where you approve those today.
     */
    fun agentChat(
        message: String,
        threadId: Int?,
        baseUrl: String,
        token: String?,
        allowCommands: Boolean = false,
        model: String? = null,
        autoApprove: Boolean = false,
    ): AgentTurn {
        val body = JsonObject().apply {
            addProperty("message", message)
            if (threadId != null) addProperty("thread_id", threadId)
            addProperty("allow_commands", allowCommands)
            addProperty("auto_approve_commands", autoApprove)
            addProperty("delegate_tools", false)
            if (!model.isNullOrBlank()) addProperty("model", model)
        }
        val builder = HttpRequest.newBuilder(URI.create("${baseUrl.trim().trimEnd('/')}/api/v1/coder/chat/send"))
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        val response = client().send(builder.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Coder chat returned ${response.statusCode()}: ${response.body()}" }
        return parseTurn(response.body())
    }

    /**
     * What one Coder turn came back with. A turn either answered or stopped to
     * ask: [pending] non-null means the server is parked on a tool call and
     * will do nothing further until [agentApprove] resolves it.
     */
    data class AgentTurn(val text: String, val threadId: Int?, val pending: PendingCall?)

    /** The call a parked turn is waiting on. `command` is null for anything but `run_command`. */
    data class PendingCall(val callId: String, val name: String, val command: String?)

    /**
     * Both `/send` and `/approve/send` answer with the same `done` payload, so
     * they are read the same way - which is also the point of the server's two
     * routes sharing one implementation.
     */
    private fun parseTurn(body: String): AgentTurn {
        val root = gson.fromJson(body, JsonObject::class.java) ?: error("No JSON from Coder chat")
        val threadId = root.get("thread_id")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

        val pending = root.get("pending_call")?.takeIf { it.isJsonObject }?.asJsonObject?.let { call ->
            PendingCall(
                callId = call.str("call_id") ?: return@let null,
                name = call.str("name") ?: "a tool call",
                command = call.getAsJsonObject("arguments")?.str("command"),
            )
        }
        // A parked turn has no answer yet, and asking for one would throw on a
        // payload whose last message is the assistant's tool call.
        if (pending != null) return AgentTurn("", threadId, pending)

        val last = root.getAsJsonArray("messages")?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: error("No messages in Coder chat's reply")
        // Seen in the wild as either a plain string or a list of content blocks
        // (Anthropic-style `{type, text}`) - whichever this server sends.
        val text = last.str("content")
            ?: last.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { p -> p.isJsonObject }?.asJsonObject?.str("text") }
                ?.joinToString("\n")?.takeIf { it.isNotBlank() }
            ?: error("No content in Coder chat's reply")
        return AgentTurn(text, threadId, null)
    }

    /**
     * Answer a call the Coder agent parked on, and let the turn carry on.
     *
     * `/approve/send` is the plain-JSON twin of the SSE `/approve` - both run
     * the server's own `run_approval`, so this client gets the same resume the
     * desktop app gets without speaking SSE. That route is what makes a real
     * permission prompt possible here at all: before it, a `pending_call` could
     * only be answered by an SSE client, so the only way to get work out of the
     * Coder agent was to auto-approve everything up front.
     *
     * A refusal is sent, not simply dropped: the server records it as the tool
     * result and tells the model, which is what stops it retrying the same
     * command forever.
     */
    fun agentApprove(
        threadId: Int,
        callId: String,
        approve: Boolean,
        baseUrl: String,
        token: String?,
        model: String? = null,
    ): AgentTurn {
        val body = JsonObject().apply {
            addProperty("thread_id", threadId)
            addProperty("call_id", callId)
            addProperty("approve", approve)
            if (!model.isNullOrBlank()) addProperty("model", model)
        }
        val builder = HttpRequest.newBuilder(URI.create("${baseUrl.trim().trimEnd('/')}/api/v1/coder/chat/approve/send"))
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        val response = client().send(builder.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Coder approve returned ${response.statusCode()}: ${response.body()}" }
        return parseTurn(response.body())
    }

    private val TERMINAL = setOf("completed", "failed", "cancelled")

    /**
     * agent-platform's process API (`desktop/crates/server`): one POST plans a
     * team and fans its tasks out concurrently server-side, so this only
     * starts the run and waits. The poll interval and deadline are arguments
     * because a test cannot afford to wait in real seconds.
     */
    fun agentTeam(
        code: String,
        baseUrl: String,
        teamId: Int,
        token: String? = null,
        pollMillis: Long = 1500,
        deadlineMillis: Long = 180_000,
    ): String {
        fun call(method: String, path: String, body: JsonObject? = null): JsonObject {
            val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
            token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
            val request =
                if (method == "POST") builder.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body ?: JsonObject()))).build()
                else builder.GET().build()
            val response = client().send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "$path returned ${response.statusCode()}: ${response.body()}" }
            return gson.fromJson(response.body(), JsonObject::class.java) ?: error("$path returned no JSON")
        }

        val created = call("POST", "/api/v1/processes", JsonObject().apply {
            addProperty("goal", "$PROMPT Review as a team.\n\n$code")
            addProperty("team_template_id", teamId)
            addProperty("auto_approve", true)
        })
        val processId = created.get("process_id").asInt

        val deadline = System.currentTimeMillis() + deadlineMillis
        var last: JsonObject
        while (true) {
            last = call("GET", "/api/v1/processes/$processId")
            val status = last.getAsJsonObject("process").get("status").asString
            if (status in TERMINAL) break
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for process $processId" }
            Thread.sleep(pollMillis)
        }

        val process = last.getAsJsonObject("process")
        val status = process.get("status").asString
        if (status != "completed") {
            error(process.get("failure_reason")?.takeIf { !it.isJsonNull }?.asString ?: "Process $status")
        }
        return last.getAsJsonArray("tasks").orEmpty().joinToString("\n\n") { t ->
            val o = t.asJsonObject
            "${o.get("role").asString}: ${o.get("output")?.takeIf { !it.isJsonNull }?.asString ?: "(no output)"}"
        }
    }

    private fun com.google.gson.JsonArray?.orEmpty() = this ?: com.google.gson.JsonArray()
}

/**
 * Where the reviewers point, editable in **Settings | Tools | Codecraft**
 * instead of by recompiling.
 *
 * Defaults are seeded from the environment the IDE was started in, so a setup
 * that already exported `AGENT_PLATFORM_*` keeps working and can then see and
 * change the values. The token is deliberately *not* here: a secret in a
 * plaintext settings file is worse than one in an environment variable, so it
 * is read from `AGENT_PLATFORM_TOKEN` at the moment of the call.
 */
class ReviewerState : BaseState() {
    var ollamaEndpoint by string(System.getenv("OLLAMA_ENDPOINT") ?: "http://localhost:11434/api/generate")
    // Blank, not a guessed name: no model name is installed on every machine,
    // and a blank "keep" is what makes the dropdown auto-select whatever that
    // machine's own Ollama actually has (see ComboBox<ModelInfo>.fill).
    var ollamaModel by string(System.getenv("OLLAMA_MODEL") ?: "")
    var agentBaseUrl by string(System.getenv("AGENT_PLATFORM_BASE_URL") ?: "http://127.0.0.1:18410")
    var agentTeamId by string(System.getenv("AGENT_PLATFORM_TEAM_TEMPLATE_ID") ?: "")
    /** Same "blank means pick from what's there" rule as [ollamaModel] - the chat's Coder model choice. */
    var agentModel by string(System.getenv("AGENT_MODEL") ?: "")
}

@Service(Service.Level.APP)
@State(name = "TrainerReviewers", storages = [Storage("trainer-reviewers.xml")])
class ReviewerSettings : SimplePersistentStateComponent<ReviewerState>(ReviewerState()) {

    /** Read at the moment of the call, so a changed variable needs no restart. */
    val agentToken: String? get() = System.getenv("AGENT_PLATFORM_TOKEN")

    companion object {
        fun get(): ReviewerSettings = service()
    }
}
