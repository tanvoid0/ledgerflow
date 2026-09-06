package io.tanvoid0.codecraft.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.tanvoid0.codecraft.isObservation
import io.tanvoid0.codecraft.Block
import io.tanvoid0.codecraft.CurriculumStore
import io.tanvoid0.codecraft.Ide
import io.tanvoid0.codecraft.Step
import io.tanvoid0.codecraft.Task
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.ScrollPaneConstants

/**
 * One step of the curriculum, rendered with the platform's own components:
 * IDE fonts, both themes, real editors for the code blocks.
 *
 * The header is plain Swing and updates in place, because ticking a task must
 * not rebuild the body — a step holds a dozen editors and recreating them on
 * every checkbox is the difference between snappy and awful.
 */
class StepView(
    private val project: Project,
    private val ide: Ide,
    /** -1 / +1. The step's own way out, so moving on does not mean the title bar. */
    private val onNav: (Int) -> Unit = {},
) : JPanel(BorderLayout()) {

    private val title = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val subtitle = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val bar = JProgressBar(0, 100).apply { preferredSize = Dimension(0, JBUI.scale(4)) }
    private val body = Wrapper()

    // Plain toolbar buttons, not toolwindow title actions - those sit in the
    // header bar and, depending on theme and how many tabs are open, can be
    // easy to miss. These are always in the same spot, always fully drawn.
    private val actions = JPanel().apply { isOpaque = false }

    private var store: CurriculumStore? = null
    private var step: Step? = null

    /** Index -> its collapsible details row, rebuilt every [rebuild]. */
    private val taskRows = mutableMapOf<Int, CollapsibleRow>()

    /** Index -> its checkbox, so the panel can scroll to, focus, and tick a task. */
    private val taskChecks = mutableMapOf<Int, JCheckBox>()

    /** Index -> the same tick, repeated at the bottom of the task's details. */
    private val bottomChecks = mutableMapOf<Int, JCheckBox>()

    init {
        actions.add(JButton("Previous", AllIcons.Actions.Back).apply { addActionListener { onNav(-1) } })
        actions.add(JButton("Tick", AllIcons.Actions.Checked).apply { addActionListener { if (!tickCurrentAndAdvance()) onNav(1) } })
        actions.add(JButton("Next", AllIcons.Actions.Forward).apply { addActionListener { onNav(1) } })
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10, 6, 10)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(title, BorderLayout.NORTH)
                add(subtitle, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
            add(bar, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    fun show(store: CurriculumStore?, step: Step?) {
        this.store = store
        this.step = step
        rebuild()
    }

    /** Cheap: numbers only, no editors touched. */
    fun refreshHeader() {
        val st = step
        val s = store
        if (st == null || s == null) {
            title.text = "No step"
            subtitle.text = ""
            bar.value = 0
            return
        }
        val done = s.doneCount(st)
        val total = st.taskList.size
        title.text = "${st.id}  ${st.title.orEmpty()}"
        subtitle.text = buildString {
            append("$done of $total tasks")
            st.estimate?.let { append("  -  $it") }
            st.stage?.let { append("  -  stage $it") }
            s.startedStepAt(st)?.let { append("  -  started ${ago(it)}") }
        }
        bar.value = if (total > 0) done * 100 / total else 0
    }

    private fun rebuild() {
        refreshHeader()
        taskRows.clear()
        taskChecks.clear()
        bottomChecks.clear()
        val s = store
        val st = step
        if (s == null || st == null) {
            body.setContent(emptyState(AllIcons.General.Information, "Nothing loaded."))
            return
        }
        body.setContent(JBScrollPane(WidthTracking(buildBody(s, st))).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
        body.revalidate()
        body.repaint()
        scrollToCurrentTask()
    }

    /**
     * Put the task you are on back on screen. A step is taller than the dock,
     * and opening step 09 at "Goal" means scrolling past six finished tasks
     * before you can do anything.
     */
    private fun scrollToCurrentTask() {
        val cb = currentCheckBox() ?: return
        // After layout, or the row has no bounds to scroll to yet.
        ApplicationManager.getApplication().invokeLater {
            cb.scrollRectToVisible(Rectangle(0, -JBUI.scale(48), cb.width, cb.height + JBUI.scale(96)))
        }
    }

    /**
     * The same task, focused — so Space ticks it and Tab walks the rest. Only
     * on request: a rebuild happens while the learner is typing in the editor,
     * and stealing focus then is the rudest thing a dock can do.
     */
    fun focusCurrentTask() {
        val cb = currentCheckBox() ?: return
        ApplicationManager.getApplication().invokeLater { cb.requestFocusInWindow() }
    }

    private fun currentCheckBox(): JCheckBox? {
        val s = store ?: return null
        val i = step?.firstUndoneIndex(s) ?: return null
        return if (i >= 0) taskChecks[i] else null
    }

    /**
     * Tick the task you are on and land on the next one — the whole loop of
     * working through a step, on one key. The task you are on is the one with
     * focus if a checkbox has it, otherwise the first undone one.
     *
     * False when there is nothing left to tick, which is the caller's cue to
     * move to the next step.
     */
    fun tickCurrentAndAdvance(): Boolean {
        val cb = taskChecks.values.firstOrNull { it.hasFocus() && !it.isSelected } ?: currentCheckBox() ?: return false
        cb.doClick()        // the listener does the store write, the collapse and the expand
        focusCurrentTask()  // which is the next undone task now
        return true
    }

    /** Tick through the checkbox, so the store write and the collapse both still happen. */
    private fun setChecked(i: Int) {
        taskChecks[i]?.takeIf { !it.isSelected }?.doClick()
    }

    private fun buildBody(s: CurriculumStore, st: Step): JComponent = panel {
        st.goal?.let { row { prose(ide, it) } }
        st.startWith?.let { row { proseComment(ide, "Start with: $it") } }

        val ideas = st.newIdeas.orEmpty()
        if (ideas.isNotEmpty()) {
            collapsibleGroup("New ideas (${ideas.size})") {
                ideas.forEach { row { prose(ide, "<b>${it.term}</b> &mdash; ${it.plain}") } }
            }
        }

        st.taskList.forEachIndexed { i, t -> task(s, st, i, t) }

        // Proof and "done when" are what you read at the *end* of a step, but
        // they sit at the bottom of every step from the moment it opens - an
        // editor's worth of height in the way of the task being worked on.
        // Folded until the step is finished, which is when they are the point.
        if (st.proof != null || st.doneWhen != null) {
            val finishing = collapsibleGroup("Finishing this step") {
                st.proof?.let { p ->
                    p.run?.let { codeBlock(project, ide, Block("", "shell", it), st.dir) }
                    p.expect?.let { row { prose(ide, "<b>Expect</b> &mdash; $it") } }
                    p.note?.let { row { proseComment(ide, it) } }
                }
                st.doneWhen?.let { row { prose(ide, "<b>Done when</b> &mdash; $it") } }
            }
            finishing.expanded = s.isComplete(st)
        }

        // The way on, at the end of the step, where you are when you finish it
        // — the title-bar arrows are a scroll away by then.
        separator()
        row {
            link("Previous step") { onNav(-1) }.applyToComponent { icon = AllIcons.Actions.Back }
            if (s.isComplete(st)) button("Next step") { onNav(1) }
            else link("Next step") { onNav(1) }.applyToComponent { icon = AllIcons.Actions.Forward }
        }
    }.apply { border = JBUI.Borders.empty(4, 10, 12, 10) }

    private fun Panel.task(s: CurriculumStore, st: Step, i: Int, t: Task) {
        separator()
        row {
            checkBox("").applyToComponent {
                taskChecks[i] = this
                isSelected = s.isDone(st, i)
                toolTipText = "Mark task ${i + 1} done"
                addActionListener {
                    bottomChecks[i]?.isSelected = isSelected
                    s.setDone(st, i, isSelected)
                    onTaskToggled(s, st, i, isSelected)
                }
            }
            prose(ide, "<b>${i + 1}.</b> ${t.action.orEmpty()}")
                .align(AlignX.FILL).resizableColumn()
        }
        // A step names the folder its commands belong in; the odd task that
        // predates that folder (step 01 creates it) names its own.
        val dir = t.dir ?: st.dir
        val hasDetails = t.why != null || t.how != null || !t.blocks.isNullOrEmpty() || t.check != null
        if (hasDetails) {
            val details = collapsibleGroup("Details") {
                t.why?.let { row { proseComment(ide, it) } }
                t.how?.let { row { prose(ide, it) } }
                t.blocks.orEmpty().forEach { codeBlock(project, ide, it, dir) }
                t.check?.let { c ->
                    // An observation is for the learner to make and tick; only a
                    // real command gets a Run button and the automatic tick.
                    c.run?.takeIf { isObservation(it) }?.let { note ->
                        row { proseComment(ide, "Check ${i + 1} &mdash; ${note.trimStart('#', ' ')}") }
                    }
                    c.run?.takeUnless { isObservation(it) }?.let { cmd ->
                        codeBlock(project, ide, Block("check ${i + 1}", "shell", cmd), dir)
                        // The task already says how to prove itself. Run that
                        // and let the proof do the ticking - exit 0 is the same
                        // answer the learner would read off the terminal.
                        row {
                            iconLink("Run the check and tick this task if it passes", AllIcons.Actions.Checked) {
                                ide.status("Checking task ${i + 1}...")
                                ide.verify(cmd, dir = dir) { ok, _ ->
                                    ide.status(
                                        if (ok) "Task ${i + 1} checks out"
                                        else "Check ${i + 1} did not pass - nothing ticked"
                                    )
                                    if (ok) setChecked(i)
                                }
                            }
                        }
                    }
                    c.expect?.let { row { proseComment(ide, "Expect: $it") } }
                }
                // The tick lives at the top of the task, above the details you
                // just read - repeat it at the bottom so ticking doesn't mean
                // scrolling back past everything you scrolled past to get here.
                row {
                    checkBox("Mark task ${i + 1} done").applyToComponent {
                        isSelected = s.isDone(st, i)
                        bottomChecks[i] = this
                        addActionListener {
                            taskChecks[i]?.isSelected = isSelected
                            s.setDone(st, i, isSelected)
                            onTaskToggled(s, st, i, isSelected)
                        }
                    }
                }
            }
            details.expanded = !s.isDone(st, i) && i == st.firstUndoneIndex(s)
            taskRows[i] = details
        }
    }

    /** Collapse the task just checked, expand the next undone one. Reopens on uncheck. */
    private fun onTaskToggled(s: CurriculumStore, st: Step, i: Int, checked: Boolean) {
        taskRows[i]?.expanded = !checked
        if (checked) {
            val next = (i + 1 until st.taskList.size).firstOrNull { !s.isDone(st, it) }
            next?.let { taskRows[it]?.expanded = true }
        }
        body.revalidate()
        body.repaint()
    }
}

private fun Step.firstUndoneIndex(s: CurriculumStore): Int =
    taskList.indices.firstOrNull { !s.isDone(this, it) } ?: -1
