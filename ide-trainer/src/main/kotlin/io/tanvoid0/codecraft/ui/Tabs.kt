package io.tanvoid0.codecraft.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.tanvoid0.codecraft.Block
import io.tanvoid0.codecraft.CurriculumStore
import io.tanvoid0.codecraft.Experiment
import io.tanvoid0.codecraft.Ide
import io.tanvoid0.codecraft.Stage
import io.tanvoid0.codecraft.Step
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Where am I, and how do I get to another step. Stages at the top level,
 * steps under them, done counts on the right — the overview the narrow step
 * panel cannot give you.
 */
class StepsTree(private val onPick: (Step) -> Unit) : JPanel(BorderLayout()) {

    private val summary = JBLabel().apply {
        border = JBUI.Borders.empty(6, 10)
        foreground = UIUtil.getContextHelpForeground()
    }
    private val rootNode = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(rootNode)
    private var store: CurriculumStore? = null
    private var current: Step? = null
    private var quiet = false
    private var expandedFor: Step? = null
    /** Lowercased. Speed search only reaches the title text on screen; this reaches into tasks. */
    private var query = ""

    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = Renderer()
        addTreeSelectionListener {
            if (quiet) return@addTreeSelectionListener
            ((it.newLeadSelectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Step)
                ?.let(onPick)
        }
    }

    /** Steps whose title alone doesn't say what's in them - "which step used docker-compose". */
    private val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search steps and tasks"
        addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onQueryChanged()
            override fun removeUpdate(e: DocumentEvent) = onQueryChanged()
            override fun changedUpdate(e: DocumentEvent) = onQueryChanged()
            private fun onQueryChanged() {
                query = text.trim().lowercase()
                rebuildTree()
            }
        })
    }

    init {
        TreeSpeedSearch.installOn(tree)
        add(JPanel(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(tree).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun show(store: CurriculumStore?) {
        this.store = store
        query = ""
        search.text = ""
        rebuildTree()
        refresh()
    }

    private fun rebuildTree() {
        rootNode.removeAllChildren()
        store?.curriculum?.stages.orEmpty().forEach { stage ->
            val steps = stage.steps.orEmpty().filter { query.isEmpty() || matches(it, query) }
            if (steps.isEmpty() && query.isNotEmpty()) return@forEach
            val node = DefaultMutableTreeNode(stage)
            steps.forEach { node.add(DefaultMutableTreeNode(it)) }
            rootNode.add(node)
        }
        model.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun matches(step: Step, q: String): Boolean {
        val text = buildString {
            append(step.id.orEmpty()).append(' ').append(step.title.orEmpty()).append(' ')
            append(step.goal.orEmpty()).append(' ').append(step.startWith.orEmpty()).append(' ').append(step.doneWhen.orEmpty())
            step.newIdeas.orEmpty().forEach { append(' ').append(it.term.orEmpty()).append(' ').append(it.plain.orEmpty()) }
            step.taskList.forEach { t ->
                append(' ').append(t.action.orEmpty()).append(' ').append(t.why.orEmpty()).append(' ').append(t.how.orEmpty())
            }
        }
        return text.lowercase().contains(q)
    }

    /** Counts changed, or the selected step did. No structure rebuild. */
    fun refresh(selected: Step? = current) {
        current = selected
        val s = store
        if (s == null) {
            summary.text = ""
            return
        }
        val (done, total) = s.totals()
        val pct = if (total > 0) done * 100 / total else 0
        val today = s.ticksToday()
        val streak = s.streakDays()
        summary.text = buildString {
            append("$done of $total tasks  ·  $pct%")
            if (today > 0) append("  ·  $today today")
            if (streak > 1) append("  ·  $streak day streak")
        }

        selected?.let { st ->
            pathTo(st)?.let { path ->
                if (st !== expandedFor) {
                    expandedFor = st
                    openOnly(path.parentPath)
                }
                quiet = true
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
                quiet = false
            }
        }
        tree.repaint()
    }

    /**
     * One stage open at a time. Five stages fully expanded is twenty rows in a
     * dock that shows eight, nineteen of which belong to a stage you are not
     * on — and only a deliberate move to another step re-folds them, so a
     * stage opened by hand stays open while you work.
     */
    private fun openOnly(stage: TreePath?) {
        if (stage == null) return
        for (i in 0 until rootNode.childCount) {
            val path = TreePath(model.getPathToRoot(rootNode.getChildAt(i)))
            if (path == stage) tree.expandPath(path) else tree.collapsePath(path)
        }
    }

    private fun pathTo(step: Step): TreePath? {
        for (i in 0 until rootNode.childCount) {
            val stage = rootNode.getChildAt(i) as DefaultMutableTreeNode
            for (j in 0 until stage.childCount) {
                val node = stage.getChildAt(j) as DefaultMutableTreeNode
                if (node.userObject === step) return TreePath(model.getPathToRoot(node))
            }
        }
        return null
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                is Stage -> append(
                    "${o.letter.orEmpty()}  ${o.name.orEmpty()}",
                    SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                )

                is Step -> {
                    val s = store
                    val done = s?.doneCount(o) ?: 0
                    val total = o.taskList.size
                    icon = when {
                        s?.isComplete(o) == true -> AllIcons.Actions.Checked
                        done > 0 -> AllIcons.Actions.Execute
                        else -> null
                    }
                    append("${o.id.orEmpty()}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(
                        o.title.orEmpty(),
                        if (o === current) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        else SimpleTextAttributes.REGULAR_ATTRIBUTES
                    )
                    append("   $done/$total", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}

/**
 * The five tools the curriculum assumes, each with the command that proves it
 * is installed — runnable here rather than copied into a terminal by hand.
 */
class SetupView(private val project: Project, private val ide: Ide) : Wrapper() {

    private var store: CurriculumStore? = null

    fun show(store: CurriculumStore?) {
        this.store = store
        val setup = store?.curriculum?.setup
        if (setup == null) {
            setContent(emptyState(AllIcons.General.Information, "This data file has no setup section."))
            return
        }
        setContent(JBScrollPane(WidthTracking(build(setup))).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
    }

    private fun iconFor(ok: Boolean?) = when (ok) {
        true -> AllIcons.Actions.Checked
        false -> AllIcons.General.Error
        null -> AllIcons.General.ContextHelp
    }

    private fun build(setup: io.tanvoid0.codecraft.Setup): JComponent = panel {
        // Each tool's own check, kept so one click can run the lot.
        val verifiers = mutableListOf<() -> Unit>()
        setup.intro?.let { row { prose(ide, it) } }
        setup.shell?.let { row { proseComment(ide, it) } }

        setup.tools.orEmpty().forEach { tool ->
            separator()
            lateinit var status: Cell<JLabel>
            // What this check said last time it ran, which is usually on
            // another day: five tools installed once should not have to be
            // re-verified every time the tab is rebuilt.
            val was = tool.name?.let { store?.check(it) }
            row {
                status = icon(iconFor(was?.ok))
                    .applyToComponent {
                        toolTipText = was?.let {
                            if (it.ok) "checked ${ago(it.at)}" else "failed ${ago(it.at)}: ${it.reason}"
                        }
                    }
                text("<b>${tool.name.orEmpty()}</b>", MAX_LINE_LENGTH_WORD_WRAP)
            }
            indent {
                tool.why?.let { row { proseComment(ide, it) } }
                tool.check?.let { chk ->
                    codeBlock(project, ide, Block(tool.name.orEmpty(), "shell", chk))
                    val verify: () -> Unit = {
                        status.component.icon = AllIcons.Actions.Refresh
                        status.component.toolTipText = "checking..."
                        ide.verify(chk) { ok, reason ->
                            status.component.icon = iconFor(ok)
                            status.component.toolTipText = if (ok) "checked just now" else "failed: $reason"
                            tool.name?.let { store?.setCheck(it, ok, reason) }
                        }
                    }
                    verifiers += verify
                    row { link("Verify") { verify() }.applyToComponent { icon = AllIcons.Actions.RunAll } }
                }
                tool.install?.let { row { proseComment(ide, "Install: $it") } }
            }
        }

        // Five tools, one click: the whole point of the tab is "am I ready",
        // and that is one question, not five.
        if (verifiers.size > 1) {
            separator()
            row {
                link("Verify all") { verifiers.forEach { it() } }
                    .applyToComponent { icon = AllIcons.Actions.RunAll }
                comment("runs every check above")
            }
        }

        val notes = setup.notes.orEmpty()
        if (notes.isNotEmpty()) {
            collapsibleGroup("Notes (${notes.size})") {
                notes.forEach { row { prose(ide, "<b>${it.h.orEmpty()}</b> &mdash; ${it.b.orEmpty()}") } }
            }
        }
    }.apply { border = JBUI.Borders.empty(8, 10, 12, 10) }
}

/**
 * Every curriculum this project has loaded, one per row, current one
 * checked. A tab rather than a gear-menu popup: switching what you are
 * learning is a thing you do while looking at the dock, not a setting you
 * dig for.
 */
class PathsView(private val onPick: (Experiment) -> Unit) : Wrapper() {

    private val model = DefaultListModel<Experiment>()
    private var current: Experiment? = null
    private var quiet = false

    private val list = JBList(model).apply {
        cellRenderer = Renderer()
        addListSelectionListener(ListSelectionListener {
            if (quiet || it.valueIsAdjusting) return@ListSelectionListener
            selectedValue?.let(onPick)
        })
    }

    init {
        setContent(JBScrollPane(list).apply { border = JBUI.Borders.empty() })
    }

    fun show(experiments: List<Experiment>, current: Experiment?) {
        this.current = current
        model.clear()
        experiments.forEach(model::addElement)
        quiet = true
        current?.let { list.setSelectedValue(it, true) }
        quiet = false
        setContent(
            if (experiments.isEmpty()) emptyState(AllIcons.General.Information, "No experiments found. Add one under ide-trainer/experiments/.")
            else JBScrollPane(list).apply { border = JBUI.Borders.empty() }
        )
    }

    private inner class Renderer : ColoredListCellRenderer<Experiment>() {
        override fun customizeCellRenderer(
            list: javax.swing.JList<out Experiment>, value: Experiment, index: Int, selected: Boolean, hasFocus: Boolean,
        ) {
            ipad = JBUI.insets(6, 10)
            icon = if (value === current) AllIcons.Actions.Checked else AllIcons.Vcs.Branch
            append(value.name.ifEmpty { value.dir.fileName?.toString().orEmpty() }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}
