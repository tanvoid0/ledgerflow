package io.tanvoid0.codecraft

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore

/** How many lines of a tool's output go into the transcript before it is summarised. */
private const val TOOL_PREVIEW_LINES = 6

/**
 * The round ceiling for the Code modes, on both backends. An edit-build-fix
 * cycle spends a round per step and the old 8 ran out mid-repair, which reads
 * as a wrong answer rather than a stopped one.
 */
private const val CODE_ROUNDS = 20

private val MENTION = Regex("""@(\S+)""")

/** The paths an "@" mention names, deduped - what [ChatController.expandMentions] goes and reads. */
internal fun mentionRefs(text: String): List<String> =
    MENTION.findAll(text).map { it.groupValues[1] }.distinct().toList()

/**
 * A plan file named off the prompt that asked for it, so a second plan does not
 * land on the first. Punctuation collapses to single dashes and the result is
 * capped - a whole pasted paragraph must not become a 400-character filename.
 */
internal fun planSlug(prompt: String): String =
    prompt.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifBlank { "plan" }

/**
 * The turns worth sending, newest kept. History is replayed verbatim every
 * turn, so without a ceiling a long conversation grows the prompt until the
 * model's context silently truncates it - which drops the *oldest* turns
 * anyway, just without anyone knowing. Dropping them here is the same outcome,
 * said out loud.
 *
 * Whole turns are dropped, never half of one: a user message whose answer was
 * cut is worse than neither. The newest turn is always kept, however long -
 * it is the question actually being asked.
 */
internal fun trimHistory(turns: List<Pair<String, String>>, budget: Int = 24_000): List<Pair<String, String>> {
    val kept = ArrayDeque<Pair<String, String>>()
    var used = 0
    for (turn in turns.asReversed()) {
        used += turn.second.length
        if (used > budget && kept.isNotEmpty()) break
        kept.addFirst(turn)
    }
    return kept.toList()
}

/**
 * Per-project conventions, read fresh each turn so editing the file does not
 * need a restart. `CLAUDE.md` is the file people already keep these in, and
 * this plugin's own project has one - the rules a human agent is given are the
 * rules the local model should get too. Capped: a rules file that grew into a
 * manual would otherwise crowd out the conversation it is meant to guide.
 */
internal fun rulesPreamble(read: (String) -> String?, limit: Int = 4_000): String {
    val body = sequenceOf("ide-trainer/RULES.md", "RULES.md", "CLAUDE.md")
        .mapNotNull { name -> read(name)?.takeIf { it.isNotBlank() }?.let { name to it } }
        .firstOrNull() ?: return ""
    return "Project rules from ${body.first} - follow them:\n${body.second.take(limit)}\n\n"
}

/**
 * The "what am I looking at" line every agentic editor sends, so "why is this
 * broken?" does not first cost a round of tool calls finding out which file
 * "this" is. Kept to a couple of lines: the open file, the caret, and what the
 * IDE already has underlined.
 *
 * Pure so the shape is testable; [Ide.editorContext] does the EDT part.
 */
internal fun editorPreamble(path: String?, caretLine: Int, problems: List<String>): String {
    if (path.isNullOrBlank()) return ""
    val head = "The user is looking at $path, caret on line $caretLine.\n"
    if (problems.isEmpty()) return head + "\n"
    return head + problems.joinToString("\n", prefix = "The IDE already reports in that file:\n", postfix = "\n\n") { "- $it" }
}

/**
 * What the chat is allowed to reach for this turn - the same three-way every
 * agentic editor now offers, mapped onto what this client can actually enforce.
 *
 * What a mode can actually enforce depends on *whose* loop is running:
 *
 * - **Ollama** runs [AgentLoop] in this process, so a mode is real. Chat and
 *   Plan are handed no tools at all, and Code (manual) prompts before every
 *   command with run-once / always-allow / deny.
 * - **Agent Platform** loops server-side, but a mode is real there too: a turn
 *   comes back *parked* on the call it wants to make, and
 *   `POST /coder/chat/approve/send` - the plain-JSON twin of that server's SSE
 *   approve route - is how this client answers it. Both backends therefore
 *   share one gate ([ChatController.approve]) and one session allowlist.
 */
enum class Mode(val label: String, val allowCommands: Boolean, val autoApprove: Boolean, val hint: String) {
    CHAT("Chat", false, false, "Answers only - no tools are offered at all."),
    PLAN("Plan", false, false, "Reads, then writes the answer to a .plan.md file instead of editing code."),
    CODE_MANUAL("Code (manual)", true, false, "Tools on. Every command asks first, on either backend."),
    CODE_AUTO("Code (auto)", true, true, "Tools on and commands run without asking. Nothing prompts.");

    override fun toString() = label
}

/** Prepended to the turn so a mode means something to the model, not just to the flags. */
internal fun Mode.preamble(): String = when (this) {
    Mode.CHAT -> "Answer the question. Do not edit files and do not run commands; explain instead.\n\n"
    Mode.PLAN -> "Produce an implementation plan in Markdown: goal, the files it touches, " +
        "ordered steps, and what could go wrong. Read whatever you need, but do not edit any file.\n\n"
    Mode.CODE_MANUAL, Mode.CODE_AUTO -> ""
}

/**
 * Everything a turn needs from whatever is drawing it. Every method is called
 * from the pooled thread a turn runs on, so an implementation over Swing
 * marshals - and [askToRun] marshals *and waits*, because the turn cannot
 * proceed until a human has answered.
 */
interface ChatUi {

    /** A labelled block in the transcript: a message, a tool's output, an error. */
    fun say(who: String, text: String)

    /** A reply that will arrive token by token starts here. */
    fun beginReply(who: String)

    fun token(piece: String)

    fun endReply()

    /** 0 run once, 1 always allow, anything else deny — dismissing is not consent. */
    fun askToRun(command: String): Int
}

/** What the panel needs back for its own chrome; the transcript was already written through [ChatUi]. */
data class TurnResult(val ok: Boolean, val usage: Reviewers.ChatUsage? = null)

/**
 * A chat turn, end to end, with no Swing in it: which backend runs, what goes
 * in front of the question, how a parked call gets approved, what is recorded
 * and what is saved.
 *
 * It lives apart from the panel because this is the half with the rules in it.
 * The approval gate in particular has to mean the same thing on both backends
 * (ADR-002), and that is a claim worth being able to test without building a
 * tool window to do it.
 */
class ChatController(
    private val project: Project,
    private val ide: Ide,
    private val ui: ChatUi,
    /** Overridden in tests; the default is this project's own chat database. */
    val db: ChatDb? = Experiments.base(project)?.let { ChatDb.open(it.resolve("ide-trainer/chats.db")) },
) {

    // Ollama has no server-side session, so this carries the turns.
    // Agent-platform keeps its own thread; only the id needs to travel.
    private val turns = mutableListOf<Pair<String, String>>()
    private var agentThreadId: Int? = null
    private var chatId: Long? = null

    /**
     * Commands the user said "always allow" to, for this session only. Not
     * persisted on purpose: an allowlist that outlives the task it was granted
     * for is how "yes, run the tests" quietly becomes standing permission.
     */
    private val allowed = mutableSetOf<String>()

    @Volatile private var stopRequested = false

    /**
     * The mode of the turn in flight, so a tool obeys the mode it was started
     * under rather than whatever the dropdown says by the time it runs.
     */
    @Volatile private var turnMode: Mode = Mode.CHAT

    private val tools by lazy { AgentTools(project, ide, db) { turnMode.autoApprove } }

    /** Stop after the current tool call. Cleared when the next turn starts. */
    fun stop() {
        stopRequested = true
    }

    // ---- the conversation --------------------------------------------------

    /** "New Chat": the old one is already saved under its own id, so this only forgets it. */
    fun clear() {
        turns.clear()
        agentThreadId = null
        chatId = null
    }

    fun history(): List<ChatDb.Chat> = db?.let { runCatching { it.chats() }.getOrNull() }.orEmpty()

    /** Reopens a saved conversation; returns its turns for the transcript, oldest first. */
    fun openSession(chat: ChatDb.Chat): List<ChatDb.Turn> {
        val saved = db?.let { runCatching { it.turns(chat.id) }.getOrNull() } ?: return emptyList()
        clear()
        chatId = chat.id
        agentThreadId = chat.threadId
        saved.forEach { turns.add(it.role to it.text) }
        return saved
    }

    /**
     * Called after every completed turn, so a conversation survives an IDE that
     * closes without warning. The title is the first thing asked, which is what
     * makes a list of past chats scannable.
     */
    private fun saveSession() {
        val store = db ?: return
        val rows = turns.map { (role, text) -> ChatDb.Turn(role, text) }
        val title = rows.firstOrNull { it.role == "user" }?.text?.lineSequence()?.first()?.take(60) ?: return
        chatId = runCatching {
            store.saveChat(chatId, title, System.currentTimeMillis(), agentThreadId, rows)
        }.getOrNull() ?: chatId
    }

    // ---- mentions ----------------------------------------------------------

    // Built once, off a keystroke - a project-wide walk on every "@" would make
    // the popup lag on anything but a toy project.
    private val projectFiles: List<Pair<String, com.intellij.openapi.vfs.VirtualFile>> by lazy {
        val root = project.guessProjectDir() ?: return@lazy emptyList()
        runReadAction {
            // Directories are kept too - a "@" mention on a folder is a real
            // thing, and rankMentions needs the path in this list to offer it.
            val out = mutableListOf<Pair<String, com.intellij.openapi.vfs.VirtualFile>>()
            ProjectFileIndex.getInstance(project).iterateContent { vf ->
                VfsUtilCore.getRelativePath(vf, root)?.let { out.add(it to vf) }
                true
            }
            out
        }
    }

    /** The paths the "@" popup offers. */
    fun projectPaths(): List<String> = projectFiles.map { it.first }

    /**
     * What the model sees: the typed text plus the content behind each
     * @mention. A folder mention expands to its file listing (from the
     * already-cached paths, no extra VFS walk) rather than every file's content
     * dumped in full - useful as "here is what's in here", not affordable as
     * "here is everything in here".
     */
    internal fun expandMentions(text: String): String {
        val refs = mentionRefs(text)
        if (refs.isEmpty()) return text
        val blocks = runReadAction {
            refs.mapNotNull { rel ->
                val vf = ide.resolve(rel) ?: return@mapNotNull null
                if (vf.isDirectory) {
                    val children = projectPaths().filter { it.startsWith("$rel/") }.sorted()
                    children.ifEmpty { null }?.let { "\n\n--- $rel/ (folder) ---\n${it.joinToString("\n")}" }
                } else {
                    val content = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: return@mapNotNull null
                    "\n\n--- $rel ---\n${content.take(4000)}"
                }
            }
        }
        return text + blocks.joinToString("")
    }

    private fun readProjectFile(rel: String): String? = runReadAction {
        ide.resolve(rel)?.takeIf { !it.isDirectory }?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }
    }

    // ---- approvals ---------------------------------------------------------

    /**
     * Whether one call may run. Auto mode and a standing "always allow" skip the
     * prompt; nothing else does. A call carrying no command is a tool the server
     * gates but this client cannot describe - it still gets asked about by name
     * rather than waved through.
     *
     * The one gate, deliberately: which loop is running must not change what
     * "Code (manual)" means, or which commands a session has already been told
     * to always allow.
     */
    internal fun approve(mode: Mode, command: String?, name: String): Boolean {
        val what = command ?: name
        if (mode.autoApprove || what in allowed) return true
        return when (ui.askToRun(what)) {
            0 -> true                            // Run Once
            1 -> { allowed.add(what); true }     // Always Allow
            else -> false                        // Deny, or dialog dismissed
        }
    }

    /**
     * The watcher the local loop reports to, on the pooled thread it runs on.
     */
    private fun watcher(mode: Mode) = object : AgentLoop.Watcher {
        override fun cancelled() = stopRequested

        override fun onToolRan(call: AgentTools.Call, output: String) {
            // Shown in the transcript as it happens: an agent that works in
            // silence for a minute is indistinguishable from a hung one.
            val lines = output.lines()
            val head = lines.take(TOOL_PREVIEW_LINES).joinToString("\n")
            val more = lines.size - TOOL_PREVIEW_LINES
            ui.say("· ${call.name}", head + if (more > 0) "\n… $more more lines" else "")
        }

        override fun approve(call: AgentTools.Call): Boolean = approve(
            mode,
            call.args.get("command")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            call.name,
        )
    }

    /**
     * Carries an Agent Platform turn through however many approvals it asks
     * for, and hands back the answer it finally gives.
     *
     * The Coder agent loops server-side, so a turn does not come back finished
     * - it comes back *parked*, with the call it wants to make. Answering it is
     * what `POST /coder/chat/approve/send` is for. A refusal is *sent*, not
     * dropped - the server records it as the tool result, which is what stops
     * the model retrying the same command forever.
     */
    internal fun resolvePending(
        first: Reviewers.AgentTurn,
        mode: Mode,
        baseUrl: String,
        token: String?,
        model: String,
    ): String {
        var turn = first
        // The same ceiling the local loop has, for the same reason: a model
        // that only ever asks must stop being asked back.
        repeat(CODE_ROUNDS) {
            val pending = turn.pending ?: return turn.text
            // The turn names its own thread; the field is the fallback for a
            // payload that omits it, and is what the *next* question resumes on.
            val threadId = turn.threadId ?: agentThreadId
                ?: return "The server parked on ${pending.name} but gave no thread to resume."
            if (stopRequested) return "[stopped with ${pending.name} still waiting for an answer]"

            val approved = approve(mode, pending.command, pending.name)
            ui.say("· ${pending.name}", if (approved) pending.command ?: "(approved)" else "(refused)")
            turn = Reviewers.agentApprove(
                threadId, pending.callId, approved, baseUrl, token, model.ifBlank { null },
            )
            agentThreadId = turn.threadId ?: agentThreadId
        }
        return turn.text.ifBlank {
            "[stopped after $CODE_ROUNDS approvals without reaching an answer]"
        }
    }

    // ---- the turn ----------------------------------------------------------

    /**
     * One turn, start to finish. Blocking, and never to be called on the EDT:
     * it makes network calls and waits on approval dialogs.
     *
     * The transcript is written through [ChatUi] as the answer arrives, so all
     * that comes back is what the panel needs for its own chrome.
     */
    fun run(text: String, mode: Mode, useAgent: Boolean, model: String, attached: String = ""): TurnResult {
        stopRequested = false
        turnMode = mode
        val settings = ReviewerSettings.get().state
        val token = ReviewerSettings.get().agentToken

        // The open file goes in front of the question, not into stored history:
        // it is true of this turn only, and replaying it later would tell the
        // model the learner is still looking at a file they closed ten turns ago.
        val here = ide.editorContext()
            ?.let { editorPreamble(it.path, it.caretLine, it.problems) }.orEmpty()
        val expanded = mode.preamble() + here + expandMentions(text) + attached

        // Only the plain Ollama path reports real counts, so null elsewhere
        // leaves the status blank rather than showing a number that isn't the
        // real usage.
        var usage: Reviewers.ChatUsage? = null
        var streamed = false

        val outcome = runCatching {
            // Recorded for both backends, not just Ollama's: `turns` is what the
            // history database saves, so an Agent Platform conversation that only
            // lived in its server-side thread would come back from the past-chats
            // list empty. Ollama additionally *sends* it.
            //
            // The raw "@path" text is what is kept, never the expanded file dump
            // - it is replayed on every later turn, and resending the dump each
            // time would balloon the prompt turn over turn.
            turns.add("user" to text)
            if (useAgent) {
                val first = Reviewers.agentChat(
                    expanded, agentThreadId, settings.agentBaseUrl.orEmpty(), token,
                    mode.allowCommands, model.ifBlank { null }, mode.autoApprove,
                )
                agentThreadId = first.threadId ?: agentThreadId
                resolvePending(first, mode, settings.agentBaseUrl.orEmpty(), token, model)
            } else {
                val sendHistory = trimHistory(turns.dropLast(1)) + ("user" to expanded)
                val endpoint = settings.ollamaEndpoint.orEmpty()
                val chosen = model.ifBlank { settings.ollamaModel.orEmpty() }
                // Chat and Plan get no tools at all - the mode says the model may
                // not touch the project, and the honest way to enforce that is to
                // not hand it the means.
                if (mode == Mode.CHAT || mode == Mode.PLAN) {
                    // Streamed, so the answer appears as it is generated and Stop
                    // can close the socket mid-reply rather than only between
                    // tool rounds.
                    ui.beginReply("Ollama")
                    streamed = true
                    val (content, u) = Reviewers.ollamaStream(
                        sendHistory, endpoint, chosen,
                        abort = { stopRequested },
                        onToken = { piece -> ui.token(piece) },
                    )
                    usage = u
                    content
                } else {
                    // What the agent has remembered about this project, and the
                    // project's own rules, go in ahead of the turn - so it starts
                    // knowing rather than rediscovering the build tool and the
                    // conventions every conversation.
                    val system = tools.projectPreamble() + rulesPreamble(::readProjectFile) + tools.memoryPreamble()
                    val withMemory = listOf("system" to system).filter { it.second.isNotBlank() } + sendHistory
                    AgentLoop(tools.schemas(), tools::needsApproval, tools::run, maxRounds = CODE_ROUNDS)
                        .run(withMemory, endpoint, chosen, watcher = watcher(mode))
                }
            }
        }

        return outcome.fold(
            onSuccess = { reply ->
                turns.add("assistant" to reply)
                // Streaming already printed it; only close the block.
                if (streamed) ui.endReply() else ui.say(if (useAgent) "Coder" else "Ollama", reply)
                if (mode == Mode.PLAN) writePlan(text, reply)
                saveSession()
                TurnResult(ok = true, usage = usage)
            },
            onFailure = { e ->
                if (streamed) ui.endReply()
                // The question went in before the call; with no answer to pair it
                // with, leaving it would make the next turn replay a question
                // that was never actually answered.
                turns.removeLastOrNull()
                ui.say("Error", e.message ?: "request failed")
                TurnResult(ok = false)
            },
        )
    }

    /**
     * Plan mode's whole point: the answer lands as a file you can edit and keep,
     * not as scrollback that dies with the tool window. Named off the asking
     * prompt so a second plan does not silently overwrite the first, and never
     * overwriting either way - [Ide.createFile] opens an existing file untouched.
     */
    private fun writePlan(prompt: String, body: String) =
        ide.createFile("${planSlug(prompt)}.plan.md", "# $prompt\n\n$body")
}
