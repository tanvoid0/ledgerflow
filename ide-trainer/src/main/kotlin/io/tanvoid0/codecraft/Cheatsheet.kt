package io.tanvoid0.codecraft

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

/**
 * The quick-command reference a path may ship next to its curriculum: groups
 * of command templates with named holes ("{branch}"), filled in from the dock
 * and copied out ready to paste.
 *
 * Fields are nullable for the same reason `Curriculum`'s are — Gson never
 * runs the constructor, so a declared default is a lie for an absent key.
 */
data class Cheatsheet(val title: String? = null, val groups: List<CheatGroup>? = null)

data class CheatGroup(val name: String? = null, val entries: List<Cheat>? = null)

data class Cheat(
    val name: String? = null,
    val cmd: String? = null,
    val note: String? = null,
    /** The IntelliJ way of doing the same thing, when there is one. */
    val ide: String? = null,
    val vars: List<CheatVar>? = null,
)

data class CheatVar(val name: String? = null, val default: String? = null, val hint: String? = null)

private val HOLE = Regex("""\{([\w.-]+)}""")

/**
 * Substitute `{name}` holes. A hole with nothing typed and no default is left
 * as it is: an unfilled `{branch}` in the copied line is a visible reminder,
 * where an empty string would silently produce `git switch ` and a confusing
 * error two seconds later.
 */
fun fill(template: String, values: Map<String, String>): String =
    HOLE.replace(template) { m ->
        val v = values[m.groupValues[1]]
        if (v.isNullOrBlank()) m.value else v
    }

/** Every hole in a template, in first-seen order, with no duplicates. */
fun holes(template: String): List<String> =
    HOLE.findAll(template).map { it.groupValues[1] }.distinct().toList()

object Cheatsheets {
    private val gson = Gson()

    fun load(file: Path): Cheatsheet? {
        if (!Files.isRegularFile(file)) return null
        return runCatching { gson.fromJson(Files.readString(file), Cheatsheet::class.java) }.getOrNull()
    }
}
