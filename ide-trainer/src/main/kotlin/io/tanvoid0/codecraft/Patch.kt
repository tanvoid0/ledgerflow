package io.tanvoid0.codecraft

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Where a `paste` block goes in the file it is pasted into.
 *
 * The model is asked *where*, never *what*: it answers with line numbers and
 * an indent, and every character written comes from the curriculum verbatim.
 * So a model having a bad day can put the snippet in the wrong place — which
 * the diff shows before anything is written — but cannot invent a line of it.
 *
 * Pure and headless on purpose: the placement rules are the part that can
 * quietly ruin a file, so they are testable without an IDE or a model.
 */
object Patch {

    /**
     * Lines are 1-based, `start` inclusive and `end` exclusive, so an insert
     * is `start == end` and a replace of lines 10..12 is `start=10, end=13`.
     */
    data class Edit(val start: Int, val end: Int, val indent: String, val reason: String) {
        val replaces: Int get() = end - start
    }

    private val gson = Gson()

    /**
     * Line by line, so the model can only answer in line numbers it can see.
     * Built as a list rather than a raw string because `trimIndent()` does its
     * work *after* interpolation, and one multi-line value pasted in the
     * middle of it flattens the indent of everything else.
     */
    fun prompt(path: String, file: String, snippet: String): String {
        val lines = file.lines()
        return listOf(
            "You are placing a snippet into an existing file. Do NOT write, fix or complete code.",
            "Reply with JSON only, no prose and no markdown:",
            """{"start": <int>, "end": <int>, "indent": "<spaces>", "reason": "<one short sentence>"}""",
            "",
            "start  1-based line the snippet goes on; the line currently there is pushed down.",
            "end    equal to start to insert. Greater than start REPLACES lines start..end-1,",
            "       which is right only when the snippet supersedes what is already there.",
            "indent whitespace to put in front of every snippet line so it matches its",
            "       surroundings; \"\" if the snippet is already indented right for that spot.",
            "Prefer inserting. Replace only what the snippet genuinely supersedes.",
            "",
            "FILE $path (${lines.size} lines):",
            lines.withIndex().joinToString("\n") { (i, l) -> "${i + 1}: $l" },
            "",
            "SNIPPET:",
            snippet.trimEnd(),
        ).joinToString("\n")
    }

    /**
     * The model's answer, or an exception naming what was wrong with it.
     *
     * Every bound is checked here rather than trusted: an out-of-range line
     * throws instead of clamping, because a number the model made up is not
     * evidence about where the snippet belongs.
     */
    fun parse(answer: String, lineCount: Int, snippetLines: Int): Edit {
        // Models fence their JSON, or introduce it, however firmly they were
        // asked not to. The outermost braces are the answer.
        val body = answer.substringAfter('{', "").substringBeforeLast('}', "")
        val json = body.takeIf { it.isNotBlank() }
            ?.let { runCatching { gson.fromJson("{$it}", JsonObject::class.java) }.getOrNull() }
            ?: error("Model did not answer with JSON")
        fun int(k: String) = json.get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

        val start = int("start") ?: error("Model gave no start line")
        val end = int("end") ?: start
        val last = lineCount + 1
        require(start in 1..last) { "Model chose line $start of a $lineCount-line file" }
        require(end in start..last) { "Model chose to replace lines $start..${end - 1}" }
        // A five-line snippet asking to swallow two hundred lines is the model
        // having lost the plot, not a refactor. ponytail: fixed slack, widen it
        // if a curriculum block ever legitimately supersedes a whole class.
        require(end - start <= snippetLines * 3 + 5) {
            "Model wanted to replace ${end - start} lines with a $snippetLines-line snippet"
        }
        // Indent is pasted in front of real code, so anything that is not
        // whitespace is dropped rather than written.
        val indent = json.get("indent")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            .takeIf { it.isBlank() && !it.contains('\n') } ?: ""
        return Edit(start, end, indent, json.get("reason")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
    }

    /** The file with the snippet in it, indented and verbatim otherwise. */
    fun apply(file: String, snippet: String, edit: Edit): String {
        val lines = file.lines()
        val body = snippet.trimEnd().lines().map { if (it.isBlank()) it else edit.indent + it }
        return (lines.take(edit.start - 1) + body + lines.drop(edit.end - 1)).joinToString("\n")
    }

    /**
     * True if the snippet's lines already sit in the file back to back,
     * whatever they are indented by — the common case of a second click, and
     * the one that would otherwise duplicate a whole block of dependencies.
     */
    fun alreadyIn(file: String, snippet: String): Boolean {
        val want = snippet.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (want.isEmpty()) return true
        val have = file.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (0..have.size - want.size).any { i -> have.subList(i, i + want.size) == want }
    }
}
