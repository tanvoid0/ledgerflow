package io.tanvoid0.codecraft.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.openapi.components.service
import io.tanvoid0.codecraft.ChatBus
import io.tanvoid0.codecraft.Cheatsheets
import io.tanvoid0.codecraft.Ide
import io.tanvoid0.codecraft.Lesson
import io.tanvoid0.codecraft.LessonEngine
import io.tanvoid0.codecraft.LessonState
import io.tanvoid0.codecraft.Session
import io.tanvoid0.codecraft.SessionEvent
import io.tanvoid0.codecraft.Step
import io.tanvoid0.codecraft.TrainerApi
import io.tanvoid0.codecraft.lessonPath
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

class TrainerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        Trainer(project, toolWindow).install()
    }
}

/**
 * The tool window: the step you are on, the steps you are not, the quick
 * commands and the setup checks — all Swing, all in the IDE's own look.
 */
class Trainer(private val project: Project, private val toolWindow: ToolWindow) : Disposable {

    private val props = PropertiesComponent.getInstance(project)
    private val ide = Ide(project)

    /** Which path is open, which step is being worked on — all of it headless. */
    private val session = Session(project, this)

    private val api = TrainerApi(
        project,
        { session.experiments },
        { session.experiment to session.store },
        { session.reload() },
    )
    // Nav from inside the step body, not just the title bar; a step that has
    // no neighbour in that direction simply does not move.
    private val stepView = StepView(project, ide) { offset -> navigate(session.stepAt(offset)) }
    private val stepsTree = StepsTree { session.select(it) }
    private val mapView = MapView { session.select(it) }
    private val setupView = SetupView(project, ide)
    private val cheatsView = CheatsView(ide)
    private val pathsView = PathsView { session.open(it) }
    private val chatView = ChatPanel(project, ide)
    private val engine: LessonEngine = LessonEngine(project, this) {
        ApplicationManager.getApplication().invokeLater {
            lessons.render()
            lessonsBar.refresh()   // the waiting count just changed
            mapView.refresh()
        }
    }
    private val lessons: LessonsPanel = LessonsPanel(ide, engine)

    /**
     * The lessons panel is dropped from the splitter for a step that teaches
     * no IDE feature, rather than kept as a third of a narrow dock saying so.
     */
    private val split = OnePixelSplitter(true, "io.tanvoid0.codecraft.split", 0.7f)

    private val lessonsBar: LessonsBar = LessonsBar(props, engine, lessons) { split.secondComponent = it }

    /** The splitter plus that one line, so the strip survives the panel folding away. */
    private val stepPane = JPanel(BorderLayout())

    /** The step tree as a permanent left sidebar, not a tab you have to switch to. */
    private val sidebar = OnePixelSplitter(false, "io.tanvoid0.codecraft.sidebar", 0.28f)

    fun install() {
        split.firstComponent = stepView
        stepPane.add(split, BorderLayout.CENTER)
        stepPane.add(lessonsBar, BorderLayout.SOUTH)
        sidebar.firstComponent = stepsTree
        sidebar.secondComponent = stepPane

        val factory = ContentFactory.getInstance()
        val main = factory.createContent(sidebar, "Step", false).apply { icon = AllIcons.Actions.Preview }
        main.setDisposer(this)
        toolWindow.contentManager.addContent(main)
        toolWindow.contentManager.addContent(
            factory.createContent(mapView, "Map", false).apply { icon = AllIcons.Graph.Layout }
        )
        toolWindow.contentManager.addContent(
            factory.createContent(setupView, "Setup", false).apply { icon = AllIcons.General.GearPlain }
        )
        toolWindow.contentManager.addContent(
            factory.createContent(cheatsView, "Cheats", false).apply { icon = AllIcons.Actions.ListFiles }
        )
        toolWindow.contentManager.addContent(
            factory.createContent(pathsView, "Paths", false).apply { icon = AllIcons.Vcs.Branch }
        )
        toolWindow.contentManager.addContent(
            factory.createContent(chatView, "Chat", false).apply { icon = AllIcons.Toolwindows.ToolWindowMessages }
        )

        val prev = prevAction()
        val next = nextAction()
        val tick = tickAction()
        toolWindow.setTitleActions(listOf(tick, prev, next, gotoAction()))
        // Bound to the dock, not globally: nothing here can shadow a key the
        // editor already owns, and the learner is in the dock when they read a
        // step anyway.
        prev.registerCustomShortcutSet(CustomShortcutSet.fromString("alt LEFT"), toolWindow.component, this)
        next.registerCustomShortcutSet(CustomShortcutSet.fromString("alt RIGHT"), toolWindow.component, this)
        // alt D for done. Alt+arrows already mean "step", so the third hand
        // movement of the loop - tick, next task, next step - joins them.
        tick.registerCustomShortcutSet(CustomShortcutSet.fromString("alt D"), toolWindow.component, this)
        toolWindow.setAdditionalGearActions(DefaultActionGroup(reloadAction()))

        // A step file edited by hand (or by an agent) should show up without
        // a reload, and so should a progress.json rewound by git.
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val dir = session.experiment?.dir?.toString()?.replace('\\', '/') ?: return
                    if (events.none { it.path.replace('\\', '/').startsWith(dir) }) return
                    ApplicationManager.getApplication().invokeLater { session.reloadFromDisk() }
                }
            })

        // Opening the dock puts you on the task you are on, focused, so Space
        // ticks it and alt+Left/Right walk the steps without touching the mouse.
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(shown: ToolWindow) {
                    // isActive, not merely shown: the dock is also "shown" when
                    // the IDE restores it at project open, and taking the caret
                    // out of the editor then is not what anyone asked for.
                    if (shown.id == toolWindow.id && shown.isActive) stepView.focusCurrentTask()
                }
            })

        val savedTab = props.getValue(LAST_TAB_KEY)
        toolWindow.contentManager.contents.firstOrNull { it.displayName == savedTab }
            ?.let { toolWindow.contentManager.setSelectedContent(it) }
        toolWindow.contentManager.addContentManagerListener(tabListener)

        // Subscribed before the first open, or the panels miss it.
        session.subscribe(::render)
        val savedExperiment = props.getValue(LAST_EXPERIMENT_KEY)
        session.open(session.experiments.firstOrNull { it.name == savedExperiment } ?: session.experiment)
        api.start()
    }

    // Remembers which tab and which experiment were open, so reopening the
    // dock lands where it was left rather than back at "Step" on experiment 1.
    private val tabListener = object : ContentManagerListener {
        override fun selectionChanged(event: ContentManagerEvent) {
            toolWindow.contentManager.selectedContent?.displayName?.let { props.setValue(LAST_TAB_KEY, it) }
        }
    }

    // ---- state -----------------------------------------------------------

    /**
     * Everything the panels do about a change, in one place. Adding a panel is
     * a line in here rather than a line in each of four refresh methods, and
     * the `when` is exhaustive — a new kind of change cannot be quietly left
     * unrendered.
     */
    private fun render(event: SessionEvent) = when (event) {
        is SessionEvent.Opened -> opened(event.failure)
        is SessionEvent.Reloaded -> {
            stepsTree.show(session.store)
            setupView.show(session.store)
        }
        is SessionEvent.Selected -> showStep(event.step, event.focus)
        is SessionEvent.Ticked -> ticked(event.completed)
    }

    /** A path opened, or did not: the panels that only change with the path. */
    private fun opened(failure: String?) {
        val exp = session.experiment
        exp?.name?.let { props.setValue(LAST_EXPERIMENT_KEY, it) }
        pathsView.show(session.experiments, exp)
        cheatsView.show(exp?.cheatsheet?.takeIf { it.isNotBlank() }
            ?.let { Cheatsheets.load(exp.dir.resolve(it)) })
        if (failure != null) {
            stepsTree.show(null)
            mapView.show(null, emptyList()) { emptyMap() }
            setupView.show(null)
            lessons.showMessage(failure)
            return
        }
        lessons.showMessage(null)   // a failure message from a previous open is stale now
        stepsTree.show(session.store)
        mapView.show(session.store, exp?.lessons.orEmpty()) { session.store?.lessonStates().orEmpty() }
        setupView.show(session.store)
    }

    private fun showStep(step: Step?, focus: Boolean) {
        stepView.show(session.store, step)
        stepsTree.refresh(step)
        mapView.refresh(step)
        engine.arm(session.experiment, session.store, step?.id)
        lessons.render()
        lessonsBar.refresh()
        toolWindow.contentManager.let { cm -> cm.getContent(0)?.let { cm.setSelectedContent(it) } }
        // The caret only moves for a step the learner asked for, never for one
        // restored under them while they are typing somewhere else.
        if (focus) stepView.focusCurrentTask()
    }

    /** A task was ticked: numbers only, and a nudge when the step just closed. */
    private fun ticked(completed: Step?) {
        stepView.refreshHeader()
        stepsTree.refresh(session.selected)
        mapView.refresh()
        completed?.let(::announceComplete)
    }

    /** A step the learner asked for: the difference from [Session.select] is the focus. */
    private fun navigate(step: Step?) = session.select(step, focus = true)

    private fun announceComplete(st: Step) {
        val next = session.store?.currentStep()
        NotificationGroupManager.getInstance().getNotificationGroup("Codecraft")
            .createNotification("Step ${st.id} complete", st.title.orEmpty(), NotificationType.INFORMATION)
            .also { n ->
                if (next != null && next !== st) {
                    n.addAction(object : AnAction("Open Step ${next.id}") {
                        override fun actionPerformed(e: AnActionEvent) {
                            navigate(next)
                            n.expire()
                        }
                    })
                }
            }
            .notify(project)
    }

    // ---- actions ---------------------------------------------------------

    private fun action(text: String, icon: javax.swing.Icon?, enabled: () -> Boolean = { true }, perform: () -> Unit) =
        object : AnAction(text, null, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun prevAction() =
        action("Previous Step", AllIcons.Actions.Back, { session.stepAt(-1) != null }) { navigate(session.stepAt(-1)) }

    private fun nextAction() =
        action("Next Step", AllIcons.Actions.Forward, { session.stepAt(1) != null }) { navigate(session.stepAt(1)) }

    /**
     * The loop of working through a step, on one key: tick the task you are
     * on, land on the next one, and when the step has nothing left, move to
     * the next step.
     */
    private fun tickAction() =
        action("Tick Task and Move On (Alt+D)", AllIcons.Actions.Checked, { session.selected != null }) {
            if (!stepView.tickCurrentAndAdvance()) session.stepAt(1)?.let(::navigate)
        }

    private fun gotoAction() = action("Go to Step", AllIcons.Actions.Find, { session.store != null }) {
        val steps = session.store?.steps.orEmpty()
        val labels = steps.associateBy { st ->
            "${st.id}  ${st.title.orEmpty()}   ${session.store?.doneCount(st)}/${st.taskList.size}"
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels.keys.toList())
            .setTitle("Go to Step")
            .setItemChosenCallback { labels[it]?.let(::navigate) }
            .createPopup()
            .showInFocusCenter()
    }

    private fun reloadAction() = action("Reload", AllIcons.Actions.Refresh) { session.reload() }

    override fun dispose() {
        toolWindow.contentManager.removeContentManagerListener(tabListener)
        api.stop()
        session.save()
        // The service outlives this window, and its callback captures the chat
        // panel - left set, closing and reopening the tool window would leak a
        // panel per cycle and send selections to a dead one.
        project.service<ChatBus>().attach = null
    }

    private companion object {
        const val LAST_TAB_KEY = "io.tanvoid0.codecraft.lastTab"
        const val LAST_EXPERIMENT_KEY = "io.tanvoid0.codecraft.lastExperiment"
    }
}
