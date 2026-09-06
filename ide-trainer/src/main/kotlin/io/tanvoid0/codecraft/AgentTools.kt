package io.tanvoid0.codecraft

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * The tools the chat may call, and the code behind them.
 *
 * This is the half agent-platform keeps on its own server and Ollama never had
 * at all: a loop where the model asks for a file, gets it, and asks for the
 * next thing. Running it *here* rather than server-side buys the one thing a
 * remote loop cannot give - a place to stand between the model and the project.
 * Every call passes through [approve] first, which is why "run once / always
 * allow / deny" is possible at all (see PROGRESS.md for why the server-side
 * route could not offer it).
 *
 * Nothing here is new machinery: reads go through [Ide.resolve], commands
 * through [Ide.runCapturing]. Editing *is* a tool since ADR-002, but never an
 * unseen one - "the model never edits" became "the model never edits unseen".
 *
 * The two gates are deliberately different, and edits do *not* use
 * [needsApproval]. A command is approved by a yes/no dialog it can be
 * described in; an edit is approved by looking at the diff, which only this
 * class can compute. Routing edits through both would prompt twice for one
 * change, so [applyOrAsk] owns their gate: [Ide.confirmEdit] in Code (manual),
 * and in auto a bare [Ide.applyEdit] - no dialog, but still one undoable write
 * command per file, so Ctrl+Z and git stay the net.
 */
/**
 * How many times `needle` occurs in `haystack`, counting non-overlapping
 * matches. This decides whether `edit_file` proceeds or refuses, so it is
 * top-level and tested: miscounting one way silently edits the wrong
 * occurrence, and the other way refuses an edit that was perfectly clear.
 *
 * Advances past each match rather than by one character - `split().size - 1`
 * gets "aaa"/"aa" wrong, and so does a naive scan.
 */
internal fun countMatches(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var i = haystack.indexOf(needle)
    while (i >= 0) {
        count++
        i = haystack.indexOf(needle, i + needle.length)
    }
    return count
}

class AgentTools(
    private val project: Project,
    private val ide: Ide,
    /** Null when the project has no database - the task and memory tools then say so. */
    private val db: ChatDb? = null,
    /** True in Code (auto): edits apply without the diff dialog, still undoably. */
    private val autoApprove: () -> Boolean = { false },
) {

    /** A call the model wants to make. */
    data class Call(val name: String, val args: JsonObject)

    /**
     * Whether a call needs the loop to ask the watcher before it runs. Only
     * commands: edits gate themselves inside [applyOrAsk] on the diff dialog,
     * and routing them through here too would prompt twice for one change.
     */
    fun needsApproval(name: String) = name == "run_command"

    /**
     * Ollama's function-calling schema. Descriptions are prompt, not
     * documentation - they are the only thing telling the model when to reach
     * for each one, so they say when to use it rather than what it does.
     */
    fun schemas(): JsonArray = JsonArray().apply {
        add(tool("read_file", "Read a file from this project. Use before answering anything about existing code.", mapOf(
            "path" to "Project-relative path, e.g. src/main/kotlin/Foo.kt",
        )))
        add(tool("list_dir", "List what is in a directory. Use to find your way around before guessing a path.", mapOf(
            "path" to "Project-relative directory, or \"\" for the project root",
        )))
        add(tool("search", "Find files whose path or name contains a string. Use when you do not know where something lives.", mapOf(
            "query" to "Substring to look for in file paths",
        )))
        add(tool("grep", "Search file CONTENTS for a string; matches come back as path:line: text. Use to find where something is defined or used - `search` only looks at file names.", mapOf(
            "query" to "Text to look for inside files, case-insensitive",
        )))
        add(tool("run_command", "Run a shell command in the project root and read its output. Use for builds, tests and git. The user must approve each one.", mapOf(
            "command" to "The command line to run",
        )))
        add(tool("edit_file", "Change part of an existing file. old_string must appear exactly once - copy it from read_file, whitespace included, with enough surrounding lines to make it unique. The user may refuse the edit.", mapOf(
            "path" to "Project-relative path of the file to change",
            "old_string" to "The exact text to replace, copied from the file",
            "new_string" to "What to put in its place",
        )))
        add(tool("write_file", "Create a new file, or replace one entirely. Prefer edit_file for changes to existing files.", mapOf(
            "path" to "Project-relative path",
            "content" to "The whole contents of the file",
        )))
        add(tool("add_task", "Record a step you intend to take, when a job has several. Add them all up front, then work through them.", mapOf(
            "text" to "One short line describing the step",
        )))
        add(tool("complete_task", "Mark a task done once you have actually finished it.", mapOf(
            "id" to "The task's id, as given by add_task or list_tasks",
        )))
        add(tool("list_tasks", "See the task list and what is still outstanding.", emptyMap()))
        add(tool("remember", "Save a durable fact about this project worth knowing in later conversations - build tool, conventions, where things live. Not for the current question.", mapOf(
            "key" to "Short identifier, so re-learning the same fact overwrites it",
            "value" to "The fact, in one or two sentences",
        )))
        add(tool("ide_context", "What the IDE currently shows: project name and root path, which files are open, the caret position, and the problems the IDE already reports. Use when the question says \"this file\" or \"here\".", emptyMap()))
    }

    private fun tool(name: String, description: String, params: Map<String, String>) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    params.forEach { (p, desc) ->
                        add(p, JsonObject().apply { addProperty("type", "string"); addProperty("description", desc) })
                    }
                })
                add("required", JsonArray().apply { params.keys.forEach { add(it) } })
            })
        })
    }

    /**
     * Runs one call and returns what the model should see. Errors come back as
     * text, never as exceptions: "no such file" is information the model can
     * act on, while a thrown exception would end the whole turn over a bad
     * guess at a path.
     */
    fun run(call: Call): String = runCatching {
        when (call.name) {
            "read_file" -> readFile(arg(call, "path"))
            "list_dir" -> listDir(arg(call, "path"))
            "search" -> search(arg(call, "query"))
            "grep" -> grep(arg(call, "query"))
            "run_command" -> runCommand(arg(call, "command"))
            "edit_file" -> editFile(arg(call, "path"), arg(call, "old_string"), arg(call, "new_string"))
            "write_file" -> writeFile(arg(call, "path"), arg(call, "content"))
            "add_task" -> addTask(arg(call, "text"))
            "complete_task" -> completeTask(arg(call, "id"))
            "list_tasks" -> listTasks()
            "remember" -> remember(arg(call, "key"), arg(call, "value"))
            "ide_context" -> ideContext()
            else -> "No tool named ${call.name}."
        }
    }.getOrElse { "${call.name} failed: ${it.message ?: it::class.java.simpleName}" }

    private fun arg(call: Call, name: String) = call.args.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun readFile(path: String): String {
        if (path.isBlank()) return "read_file needs a path."
        val vf = runReadAction { ide.resolve(path) } ?: return "No $path in this project."
        if (vf.isDirectory) return "$path is a directory - use list_dir."
        val text = runReadAction { VfsUtilCore.loadText(vf) }
        // Truncation is announced, so the model knows to narrow rather than
        // conclude the file simply ends there.
        return if (text.length <= FILE_LIMIT) text
        else text.take(FILE_LIMIT) + "\n\n[truncated at $FILE_LIMIT chars of ${text.length}]"
    }

    private fun listDir(path: String): String {
        val root = project.guessProjectDir() ?: return "This project has no directory."
        val dir = if (path.isBlank()) root else runReadAction { ide.resolve(path) } ?: return "No $path in this project."
        if (!dir.isDirectory) return "$path is a file - use read_file."
        val names = runReadAction { dir.children.map { if (it.isDirectory) "${it.name}/" else it.name }.sorted() }
        return if (names.isEmpty()) "(empty)" else names.joinToString("\n")
    }

    private fun search(query: String): String {
        if (query.isBlank()) return "search needs a query."
        val root = project.guessProjectDir() ?: return "This project has no directory."
        val hits = runReadAction {
            val out = mutableListOf<String>()
            ProjectFileIndex.getInstance(project).iterateContent { vf ->
                if (!vf.isDirectory) {
                    VfsUtilCore.getRelativePath(vf, root)
                        ?.takeIf { it.contains(query, ignoreCase = true) }
                        ?.let { out.add(it) }
                }
                out.size < SEARCH_LIMIT
            }
            out
        }
        return if (hits.isEmpty()) "Nothing matching \"$query\"." else hits.joinToString("\n")
    }

    private fun grep(query: String): String {
        if (query.isBlank()) return "grep needs a query."
        val root = project.guessProjectDir() ?: return "This project has no directory."
        val hits = mutableListOf<String>()
        runReadAction {
            ProjectFileIndex.getInstance(project).iterateContent { vf ->
                // Binary and oversized files are skipped, not scanned: a match
                // inside a jar or a megabyte of minified JS is never the answer.
                if (!vf.isDirectory && !vf.fileType.isBinary && vf.length < GREP_FILE_LIMIT) {
                    val rel = VfsUtilCore.getRelativePath(vf, root)
                    val text = rel?.let { runCatching { VfsUtilCore.loadText(vf) }.getOrNull() }
                    text?.lineSequence()?.forEachIndexed { i, line ->
                        if (hits.size < GREP_LIMIT && line.contains(query, ignoreCase = true)) {
                            hits.add("$rel:${i + 1}: ${line.trim().take(200)}")
                        }
                    }
                }
                hits.size < GREP_LIMIT
            }
        }
        return when {
            hits.isEmpty() -> "Nothing containing \"$query\"."
            // Announced, same rule as read_file's truncation: a capped list
            // must not read as a complete one.
            hits.size >= GREP_LIMIT -> hits.joinToString("\n") + "\n[stopped at $GREP_LIMIT hits - narrow the query]"
            else -> hits.joinToString("\n")
        }
    }

    /** Approval is the caller's job - by the time this runs, someone has said yes. */
    private fun runCommand(command: String): String {
        if (command.isBlank()) return "run_command needs a command."
        val run = ide.runCapturing(command)
        return "exit ${run.exit}\n${run.output.ifBlank { "(no output)" }}"
    }

    // ---- editing ---------------------------------------------------------

    /**
     * Exact-match replace, refused unless `old_string` occurs exactly once.
     *
     * The refusal is the safety property, not a limitation: a model that pasted
     * a fragment appearing three times has not said which one it meant, and
     * picking for it is how an agent silently corrupts a file. Zero matches
     * usually means it is editing a file it half-remembers - also worth being
     * told rather than guessed around. Same honesty rule as `Patch.parse`.
     */
    private fun editFile(path: String, old: String, new: String): String {
        if (path.isBlank() || old.isEmpty()) return "edit_file needs a path and a non-empty old_string."
        val vf = runReadAction { ide.resolve(path) } ?: return "No $path in this project."
        if (vf.isDirectory) return "$path is a directory."
        val before = runReadAction { VfsUtilCore.loadText(vf) }
        val hits = countMatches(before, old)
        if (hits == 0) return "old_string is not in $path. Read the file and copy the text exactly."
        if (hits > 1) return "old_string appears $hits times in $path. Include more surrounding lines so it is unique."
        val after = before.replace(old, new)
        return applyOrAsk(vf, before, after, path, "Replace ${old.lines().size} line(s) in $path")
    }

    /** Whole-file write, for new files or a full rewrite. Same gate as [editFile]. */
    private fun writeFile(path: String, content: String): String {
        if (path.isBlank()) return "write_file needs a path."
        val existing = runReadAction { ide.resolve(path) }
        if (existing == null) {
            // A file that does not exist yet has no diff to show, and creating
            // one is not destructive - Ide.createFile refuses to overwrite.
            return if (ide.createFile(path, content) != null) "Created $path." else "Could not create $path."
        }
        if (existing.isDirectory) return "$path is a directory."
        val before = runReadAction { VfsUtilCore.loadText(existing) }
        if (before == content) return "$path already says exactly this."
        return applyOrAsk(existing, before, content, path, "Rewrite $path")
    }

    /**
     * The gate itself. Manual mode shows the diff and waits; auto applies it as
     * one undoable write. Note this is *not* routed through
     * [AgentLoop.Watcher.approve] - that path shows the command dialog, and
     * sending edits through both would prompt twice for one change.
     */
    private fun applyOrAsk(vf: VirtualFile, before: String, after: String, path: String, what: String): String {
        if (!autoApprove()) {
            val ok = ide.confirmEdit(vf, before, after, what, "Proposed by the chat.")
            if (!ok) return "The user rejected this edit to $path. Do not retry it; ask what they wanted instead."
        }
        ide.applyEdit(vf, after, what)
        return "Edited $path."
    }

    // ---- task list and memory --------------------------------------------

    private fun addTask(text: String): String {
        if (text.isBlank()) return "add_task needs some text."
        val store = db ?: return NO_DB
        return "Task ${store.addTask(text, System.currentTimeMillis())} added."
    }

    private fun completeTask(id: String): String {
        val store = db ?: return NO_DB
        val n = id.trim().toLongOrNull() ?: return "complete_task needs a numeric id, got \"$id\"."
        return if (store.completeTask(n)) "Task $n done." else "No open task $n."
    }

    private fun listTasks(): String {
        val store = db ?: return NO_DB
        val all = store.tasks()
        if (all.isEmpty()) return "No tasks."
        return all.joinToString("\n") { "${if (it.done) "[x]" else "[ ]"} ${it.id}. ${it.text}" }
    }

    private fun remember(key: String, value: String): String {
        if (key.isBlank() || value.isBlank()) return "remember needs both a key and a value."
        val store = db ?: return NO_DB
        store.remember(key, value, System.currentTimeMillis())
        return "Remembered \"$key\"."
    }

    private fun ideContext(): String {
        val root = project.guessProjectDir()
        val open = ide.openFiles()
        val here = ide.editorContext()
        return buildString {
            appendLine("Project: ${project.name}")
            appendLine("Root: ${root?.path ?: "(none)"}")
            appendLine("Paths in every other tool are relative to that root.")
            if (here != null) {
                appendLine("Current file: ${here.path}, caret on line ${here.caretLine}")
                if (here.problems.isNotEmpty()) {
                    appendLine("Problems the IDE reports there:")
                    here.problems.forEach { appendLine("- $it") }
                }
            }
            if (open.isEmpty()) appendLine("No files are open.")
            else appendLine("Open tabs:\n${open.joinToString("\n") { "- $it" }}")
        }.trim()
    }

    /**
     * Where the model is standing, prepended to every Code-mode turn. Without
     * it a local model asks the user for "the project root" instead of calling
     * `list_dir("")` - it has tools that take project-relative paths and no
     * statement anywhere that such a thing exists.
     */
    fun projectPreamble(): String {
        val root = project.guessProjectDir()?.path ?: return ""
        val open = ide.openFiles(limit = 8)
        val tabs = if (open.isEmpty()) "" else " Open tabs: ${open.joinToString(", ")}."
        return "You are working inside the IntelliJ project \"${project.name}\", rooted at $root. " +
            "Every path you pass to a tool is relative to that root; \"\" means the root itself. " +
            "Do not ask the user for a path - call list_dir, search or grep and find it.$tabs\n\n"
    }

    /**
     * What the agent already knows about this project, for the system preamble.
     * Kept small on purpose: memory that grows without bound turns into a
     * second, worse conversation history prepended to every turn.
     */
    fun memoryPreamble(): String {
        val notes = db?.memories().orEmpty()
        if (notes.isEmpty()) return ""
        return notes.entries.take(MEMORY_LIMIT)
            .joinToString("\n", prefix = "What you already know about this project:\n", postfix = "\n\n") {
                "- ${it.key}: ${it.value}"
            }
    }

    private companion object {
        const val FILE_LIMIT = 12_000
        const val SEARCH_LIMIT = 60
        const val GREP_LIMIT = 100
        const val GREP_FILE_LIMIT = 500_000L
        const val MEMORY_LIMIT = 40
        const val NO_DB = "This project has no database, so that cannot be saved."
    }
}
