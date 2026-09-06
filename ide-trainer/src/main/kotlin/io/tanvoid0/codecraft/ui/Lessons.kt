package io.tanvoid0.codecraft.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.tanvoid0.codecraft.Ide
import io.tanvoid0.codecraft.Lesson
import io.tanvoid0.codecraft.LessonEngine
import io.tanvoid0.codecraft.LessonState
import io.tanvoid0.codecraft.lessonPath
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

private const val LESSONS_OPEN_KEY = "io.tanvoid0.codecraft.lessonsOpen"

/**
 * The IDE features worth learning at this step, and whether you have used them
 * yet. The engine watches the real IDE for each one; this only says what it is
 * waiting for.
 *
 * Same DSL as the rest of the tool window, so the prose reflows with the dock
 * instead of wrapping at a hardcoded width, and "skip" is a link rather than a
 * button stretched to the height of its row.
 */
class LessonsPanel(private val ide: Ide, private val engine: LessonEngine) : Wrapper() {

    private var message: String? = null

    fun showMessage(text: String?) {
        message = text
        render()
    }

    /** Nothing to teach and nothing to report: the panel is not worth its half of the dock. */
    fun hasSomethingToShow() = message != null || engine.lessons().isNotEmpty()

    fun render() {
        setContent(JBScrollPane(WidthTracking(build())).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
        revalidate()
        repaint()
    }

    private fun build(): JComponent = panel {
        message?.let {
            row { cell(emptyState(AllIcons.General.Warning, it)).align(AlignX.FILL) }
            return@panel
        }
        val armed = engine.lessons()
        row { text("<b>IDE lessons</b>", MAX_LINE_LENGTH_WORD_WRAP) }
        if (armed.isEmpty()) {
            row { comment("No IDE feature is worth teaching at this step. Carry on with the tasks.", MAX_LINE_LENGTH_WORD_WRAP) }
            return@panel
        }
        row {
            comment(
                "Features this step is a good excuse to learn. Ticked when you actually use one - " +
                    "nothing here blocks the curriculum.", MAX_LINE_LENGTH_WORD_WRAP
            )
        }
        armed.forEach { lesson(it) }
    }.apply { border = JBUI.Borders.empty(8, 10, 12, 10) }

    private fun Panel.lesson(l: Lesson) {
        val state = engine.stateOf(l)
        separator()
        row {
            icon(
                when (state) {
                    LessonState.DONE -> AllIcons.Actions.Checked
                    LessonState.SKIPPED -> AllIcons.General.InspectionsOK
                    LessonState.PENDING -> AllIcons.General.Balloon
                }
            )
            text("<b>${l.title}</b>", MAX_LINE_LENGTH_WORD_WRAP).align(AlignX.FILL).resizableColumn()
        }
        indent {
            row { prose(ide, l.teach) }
            when (state) {
                // The feature itself, one click away — and doing it here
                // trips the same detector doing it by hand would, so the
                // lesson ticks itself.
                LessonState.PENDING -> row {
                    when (l.trigger.type) {
                        "action" -> iconLink("Do it here", AllIcons.Actions.Execute) { ide.runAction(l.trigger.id) }
                        "toolWindow" ->
                            iconLink("Open ${l.trigger.id}", AllIcons.Actions.Preview) { ide.openToolWindow(l.trigger.id) }
                    }
                    // Where the lesson happens, when it names a place: the
                    // file if it exists, otherwise the folder it goes in,
                    // selected and ready for New → File.
                    lessonPath(l)?.let { path ->
                        iconLink("Go to $path", AllIcons.General.OpenDisk) { ide.reveal(path) }
                    }
                    comment("waiting for you to do it")
                    link("skip") { engine.skip(l) }.applyToComponent { icon = AllIcons.Actions.Close }
                }

                LessonState.SKIPPED -> l.fallbackTip.takeIf { it.isNotBlank() }
                    ?.let { tip -> row { proseComment(ide, tip) } }

                LessonState.DONE -> Unit
            }
        }
    }
}

/**
 * One line at the bottom saying what is waiting, and opening the panel when
 * clicked. Folded, the lessons cost a row instead of a third of the dock —
 * they are worth knowing about, but never at the expense of the step being
 * worked on. Whether they sit open is remembered per project, because that is
 * a preference rather than a per-step decision.
 *
 * [fold] is handed the panel to show, or null to take it off screen; the
 * splitter it goes into belongs to the tool window, not to this strip.
 */
class LessonsBar(
    private val props: PropertiesComponent,
    private val engine: LessonEngine,
    private val panel: LessonsPanel,
    private val fold: (JComponent?) -> Unit,
) : JPanel(BorderLayout()) {

    private val label = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(3, 10)
    }
    private val toggle = ActionLink("") { open = !open; refresh() }
        .apply { border = JBUI.Borders.empty(3, 10) }

    private var open: Boolean
        get() = props.getBoolean(LESSONS_OPEN_KEY, false)
        set(v) = props.setValue(LESSONS_OPEN_KEY, v)

    init {
        add(label, BorderLayout.WEST)
        add(toggle, BorderLayout.EAST)
        border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
    }

    /** Re-reads the engine: the step changed, or a lesson just ticked itself. */
    fun refresh() {
        val armed = engine.lessons()
        val pending = armed.count { engine.stateOf(it) == LessonState.PENDING }
        isVisible = panel.hasSomethingToShow()
        if (!isVisible) {
            fold(null)
            return
        }
        label.text = when {
            armed.isEmpty() -> "IDE lessons"
            pending > 0 -> "IDE lessons - $pending waiting"
            else -> "IDE lessons - all done"
        }
        toggle.text = if (open) "hide" else "show"
        fold(if (open) panel else null)
    }
}
