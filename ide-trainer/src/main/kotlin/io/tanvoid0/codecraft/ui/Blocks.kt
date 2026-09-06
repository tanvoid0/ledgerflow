package io.tanvoid0.codecraft.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.tanvoid0.codecraft.Block
import io.tanvoid0.codecraft.GOTO
import io.tanvoid0.codecraft.INVOKE
import io.tanvoid0.codecraft.Ide
import io.tanvoid0.codecraft.sourcePath
import io.tanvoid0.codecraft.linkify
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.event.HyperlinkEvent

/**
 * A code block: its label, the things you can do with it, and the body in a
 * read-only IDE editor of whatever file type the label implies — so a `.java`
 * block gets Java highlighting from the plugins the IDE already has, and no
 * new dependency is needed to get it.
 */
internal fun Panel.codeBlock(project: Project, ide: Ide, b: Block, dir: String? = null) {
    if (!b.label.isNullOrBlank()) {
        row {
            icon(blockIcon(b))
            comment("${kindTag(b.kind)} &middot; ${b.label}", MAX_LINE_LENGTH_WORD_WRAP)
        }
    }
    // A `file` or `paste` block is the answer, not the exercise: reading it is
    // how a step gets finished without ever being done. It stays one click
    // away, folded, so asking for it is a decision rather than the default.
    if (isSolution(b)) collapsibleGroup(solutionLabel(b)) { blockActions(ide, b, dir); blockBody(project, b) }
    else {
        blockActions(ide, b, dir)
        blockBody(project, b)
    }
}

/** Blocks that hand over code the learner is supposed to write. */
private fun isSolution(b: Block) = b.kind == "file" || b.kind == "paste"

private fun solutionLabel(b: Block): String {
    val lines = b.body.orEmpty().trimEnd().lines().size
    return if (b.kind == "file") "Show the solution file ($lines lines)"
    else "Show the snippet ($lines lines)"
}

private fun Panel.blockActions(ide: Ide, b: Block, dir: String?) {
    val body = b.body.orEmpty().trimEnd()
    val path = sourcePath(b.label.orEmpty(), b.body)
    // Icon-only, not icon+label: a dock this narrow runs out of columns at
    // three text links, and a tooltip costs nothing a label didn't already.
    row {
        if (b.kind == null || b.kind == "shell")
            iconLink("Run", AllIcons.Actions.Execute, showText = true) { ide.runInTerminal(body, dir) }
        iconLink("Copy", AllIcons.Actions.Copy, showText = true) { ide.copy(body) }
        // Only where there is code to review: "review this for bugs" on a
        // docker command or a paragraph of prose wastes a model call and a
        // click.
        if (b.kind == "file" || b.kind == "paste") {
            iconLink("Review", AllIcons.Actions.IntentionBulb) { ide.reviewWithOllama(body) }
            iconLink("Deep Review", AllIcons.Actions.RunAll) { ide.reviewWithAgentPlatform(body) }
        }
        // Labelled, not icon-only: on a paste block this is the way out of
        // "the model put it in the wrong place" - open the file and edit it by
        // hand - so it has to be findable without hovering every icon.
        if (path != null)
            iconLink("Open", AllIcons.General.OpenDisk, showText = true) {
                // A `file` block can write the file itself; a `paste` block is a
                // fragment, so the file has to come from an earlier step.
                ide.openFile(
                    path, dir = dir,
                    hint = if (b.kind == "file") "Use <b>Create</b> on this block to write it, then Open."
                    else "This block is a fragment. The file comes from an earlier step - create it there first.",
                )
            }
        // A file block is the whole file: write it rather than make the learner
        // paste it, and once it exists show the diff instead of doing nothing.
        if (path != null && b.kind == "file") {
            iconLink("Create", AllIcons.General.Add) { ide.createFile(path, body, dir) }
            iconLink("Update to this (shows a diff)", AllIcons.Actions.Diff) { ide.updateFile(path, body, dir) }
        }
        // A paste block is a fragment, and where it goes is the hard part. The
        // path is optional: without one it patches the open file, which is the
        // only sane reading of "call it from the controller".
        if (b.kind == "paste")
            iconLink("Patch in (asks the model where)", AllIcons.Vcs.Patch_applied) { ide.patchFile(path, body, dir) }
    }
}

private fun Panel.blockBody(project: Project, b: Block) {
    row { cell(viewer(project, b.body.orEmpty().trimEnd(), fileTypeFor(b))).align(AlignX.FILL).resizableColumn() }
}

/**
 * Curriculum prose, with every file, folder and shortcut it names turned into
 * something you can click: the step already tells you to select a folder or
 * press a key, so the panel may as well do it rather than leave you to find
 * it in a tree.
 */
internal fun Row.prose(ide: Ide, s: String) = text(linkify(s), MAX_LINE_LENGTH_WORD_WRAP, linkAction(ide))

/** The same, in the muted comment style. */
internal fun Row.proseComment(ide: Ide, s: String) = comment(linkify(s), MAX_LINE_LENGTH_WORD_WRAP, linkAction(ide))

/** Trainer links are handled here; anything else is an ordinary web link. */
private fun linkAction(ide: Ide) = object : HyperlinkEventAction {
    override fun hyperlinkActivated(e: HyperlinkEvent) {
        val href = e.description.orEmpty()
        when {
            href.startsWith(GOTO) -> ide.reveal(href.removePrefix(GOTO))
            href.startsWith(INVOKE) -> ide.runShortcut(href.removePrefix(INVOKE))
            else -> HyperlinkEventAction.HTML_HYPERLINK_INSTANCE.hyperlinkActivated(e)
        }
    }
}

/**
 * A `link()` with the label moved to the tooltip, so five of them fit one row
 * of a dock. `showText` keeps the label visible for the couple of actions
 * (Run, Copy) worth naming outright rather than leaving to an icon and a
 * tooltip nobody hovers long enough to read.
 */
internal fun Row.iconLink(tooltip: String, icon: Icon, showText: Boolean = false, action: () -> Unit) {
    link(tooltip) { action() }.applyToComponent {
        if (!showText) text = ""
        this.icon = icon
        toolTipText = tooltip
        border = JBUI.Borders.empty(0, 2)
    }
}

private fun viewer(project: Project, text: String, type: FileType) =
    EditorTextField(text, project, type).apply {
        setOneLineMode(false)
        isViewer = true
        addSettingsProvider { ed ->
            ed.setHorizontalScrollbarVisible(true)
            ed.setVerticalScrollbarVisible(false)
            ed.settings.apply {
                isLineNumbersShown = false
                isFoldingOutlineShown = false
                isCaretRowShown = false
                isRightMarginShown = false
                additionalLinesCount = 0
                additionalColumnsCount = 0
            }
        }
    }

private fun fileTypeFor(b: Block): FileType {
    val name = when (b.kind) {
        null, "shell" -> "trainer.sh"
        else -> sourcePath(b.label.orEmpty(), b.body) ?: "trainer.txt"
    }
    val ft = FileTypeManager.getInstance().getFileTypeByFileName(name)
    return if (ft is UnknownFileType || ft.isBinary) PlainTextFileType.INSTANCE else ft
}

private fun kindTag(kind: String?) = when (kind) {
    "file" -> "file"
    "paste" -> "paste into"
    "note" -> "reference"
    else -> "run"
}

private fun kindIcon(kind: String?): Icon = when (kind) {
    "file" -> AllIcons.FileTypes.Any_type
    "paste" -> AllIcons.Actions.MenuPaste
    "note" -> AllIcons.General.Information
    else -> AllIcons.Nodes.Console
}

/**
 * The label already names a path ("src/Foo.kt"), and the IDE already ships an
 * icon per language/tool via its bundled file types (Kotlin, Docker, YAML...).
 * Prefer that real logo over the generic file/paste glyph; fall back to it
 * only when the label doesn't resolve to anything more specific than plain
 * text.
 */
private fun blockIcon(b: Block): Icon {
    val ft = if (b.kind == "file" || b.kind == "paste") fileTypeFor(b) else null
    return if (ft != null && ft != PlainTextFileType.INSTANCE) ft.icon ?: kindIcon(b.kind) else kindIcon(b.kind)
}

/**
 * A centered icon over a line of text, for a panel that has nothing else to
 * show - "no data" reads as a state, not a stray label stuck in the corner.
 */
internal fun emptyState(icon: Icon, text: String): JComponent =
    JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(24, 10)
        add(JBLabel(text, icon, javax.swing.SwingConstants.CENTER).apply {
            foreground = UIUtil.getContextHelpForeground()
            iconTextGap = JBUI.scale(6)
        })
    }

/**
 * "23m ago", "3h ago", "2d ago". A dock has no room for "2 days, 4 hours",
 * and the order of magnitude is the part anyone reads.
 */
internal fun ago(at: Long): String {
    val mins = (System.currentTimeMillis() - at) / 60_000
    return when {
        mins < 1L -> "just now"
        mins < 60L -> "${mins}m ago"
        mins < 60L * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

/**
 * A scroll view as wide as the viewport rather than as wide as its content, so
 * wrapped text reflows when the dock is resized instead of growing a
 * horizontal scrollbar.
 */
internal class WidthTracking(view: JComponent) : JPanel(BorderLayout()), Scrollable {
    init {
        add(view, BorderLayout.CENTER)
        isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
