package io.tanvoid0.codecraft

import javax.swing.KeyStroke

/**
 * A lesson or a task names things the IDE can do for you — a file, a folder,
 * a shortcut — and then leaves the learner to go find them by hand. These
 * turn those mentions into links; `Ide` does what they say when one is
 * clicked.
 *
 * Pure on purpose: the matching is the part that is easy to get wrong, and a
 * link that goes nowhere is worse than plain text.
 */
const val GOTO = "trainer:goto:"
const val INVOKE = "trainer:do:"

private val TAG = Regex("<[^>]*>")

/**
 * Extensions the curriculum actually writes. An open-ended rule would turn
 * "e.g." and "vs." into links; a shorter list would miss real files.
 */
private const val EXT = "java|kt|kts|xml|yml|yaml|json|sql|md|txt|properties|gradle|http|sh|ps1|ts|js|html|css|tf|toml|conf"

/**
 * A path inside a sentence: anything with a slash in it (folder or file), or
 * a bare filename with one of the known extensions. Both halves are what a
 * step actually says — "select src/main/resources/db/migration", "open
 * Wallet.java".
 */
private val PROSE_PATH = Regex("""(?<![\w/.@-])((?:[\w.@+-]+/)+[\w.@+-]+|[\w@+-]+\.(?:$EXT))(?![\w/])""")

/** "Ctrl+Alt+Insert", "Shift+F10" — a modifier plus a key, which is what a keymap is indexed by. */
private val SHORTCUT = Regex("""\b((?:Ctrl|Control|Alt|Option|Shift|Cmd|Command|Meta)\+[\w+]+)""")

/**
 * The same HTML back with paths and shortcuts wrapped in trainer links. Tags
 * are copied through untouched, so an `href` or a `<code>` in the source
 * never gets rewritten — only the text between tags is scanned.
 */
fun linkify(html: String): String = buildString {
    var i = 0
    while (i < html.length) {
        val lt = html.indexOf('<', i)
        append(linkifyText(html.substring(i, if (lt < 0) html.length else lt)))
        if (lt < 0) break
        val gt = html.indexOf('>', lt)
        if (gt < 0) {
            append(html, lt, html.length)
            break
        }
        append(html, lt, gt + 1)
        i = gt + 1
    }
}

private fun linkifyText(s: String) = s
    .replace(PROSE_PATH) { "<a href='$GOTO${it.value}'>${it.value}</a>" }
    .replace(SHORTCUT) { "<a href='$INVOKE${it.value}'>${it.value}</a>" }

/** The first path a piece of prose names, markup ignored. */
fun firstPath(text: String?): String? =
    text?.replace(TAG, "")?.let { PROSE_PATH.find(it)?.value }

/**
 * Where a lesson is asking you to be. The trigger names it outright when it
 * is a file event; otherwise the teaching text or the fallback tip does
 * ("touch src/main/resources/db/migration/V1__init.sql"). A wildcard trigger
 * (`*.sql`) names no path and is skipped.
 */
fun lessonPath(l: Lesson): String? =
    sequenceOf(l.trigger.id, l.teach, l.fallbackTip).firstNotNullOfOrNull { firstPath(it) }

/**
 * "Ctrl+Alt+Insert" as the keystroke the keymap is indexed by, so the action
 * behind a shortcut is looked up in the learner's own keymap rather than
 * hardcoded from the default one. Null for anything that is not a shortcut.
 */
fun keyStrokeOf(shortcut: String): KeyStroke? {
    val parts = shortcut.trim().split('+').filter { it.isNotBlank() }
    if (parts.size < 2) return null
    val mods = parts.dropLast(1).map {
        when (it.lowercase()) {
            "ctrl", "control" -> "ctrl"
            "alt", "option" -> "alt"
            "shift" -> "shift"
            "cmd", "command", "meta" -> "meta"
            else -> return null
        }
    }
    return runCatching {
        KeyStroke.getKeyStroke("${mods.joinToString(" ")} pressed ${parts.last().uppercase()}")
    }.getOrNull()
}
