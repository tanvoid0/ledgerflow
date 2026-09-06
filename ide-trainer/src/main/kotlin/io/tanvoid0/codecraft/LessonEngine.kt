package io.tanvoid0.codecraft

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener

/** "done" once a detector fired, "skipped" if the learner gave up on it. */
enum class LessonState { PENDING, DONE, SKIPPED }

/**
 * Watches what the learner does in the IDE and marks lessons done when the
 * feature they teach is actually used. Detection is on ordinary platform
 * listeners — nothing from the IDE Features Trainer's internal lesson API,
 * which is not a third-party contract.
 *
 * Only lessons for the current step are armed, so pressing Ctrl+N in step 12
 * does not retroactively tick step 01's lesson.
 */
class LessonEngine(
    private val project: Project,
    parent: Disposable,
    private val onChange: () -> Unit,
) {
    private var experiment: Experiment? = null

    /**
     * Lesson state lives in the experiment's database, alongside the ticks —
     * reached through the store that owns that connection rather than through
     * a second one of our own. One file, one connection, one owner.
     */
    private var store: CurriculumStore? = null
    private var armed: List<Lesson> = emptyList()
    private val states = HashMap<String, LessonState>()

    init {
        val bus = project.messageBus.connect(parent)

        bus.subscribe(AnActionListener.TOPIC, object : AnActionListener {
            override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: com.intellij.openapi.actionSystem.AnActionResult) {
                ActionManager.getInstance().getId(action)?.let { fire("action", it) }
            }
        })

        bus.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                fire("run", executorId)
            }
        })

        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                fire("fileOpen", file.path)
            }
        })

        bus.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) {
                fire("toolWindow", toolWindow.id)
            }
        })

        // "Create X" is the most common kind of curriculum task, and the file
        // appearing is the honest proof of it — no gesture to guess at.
        bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                events.filterIsInstance<VFileCreateEvent>().forEach { fire("fileCreate", it.path) }
            }
        })

        bus.subscribe(XBreakpointListener.TOPIC, object : XBreakpointListener<XBreakpoint<*>> {
            override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
                fire("breakpoint", breakpoint.type.id)
            }
        })
    }

    fun arm(exp: Experiment?, store: CurriculumStore?, step: String?) {
        if (exp !== experiment || store !== this.store) {
            experiment = exp
            this.store = store
            states.clear()
            states.putAll(store?.lessonStates().orEmpty())
        }
        armed = exp?.lessonsFor(step).orEmpty()
    }

    fun lessons(): List<Lesson> = armed
    fun stateOf(l: Lesson): LessonState = states[l.id] ?: LessonState.PENDING

    fun skip(l: Lesson) = mark(l, LessonState.SKIPPED)

    private fun fire(kind: String, value: String) {
        val hit = armed.firstOrNull { stateOf(it) == LessonState.PENDING && triggerMatches(it.trigger, kind, value) }
            ?: return
        // Listeners run on the EDT during action dispatch; defer so the UI
        // rebuild does not happen inside the action being detected.
        ApplicationManager.getApplication().invokeLater { mark(hit, LessonState.DONE) }
    }

    private fun mark(l: Lesson, s: LessonState) {
        states[l.id] = s
        store?.setLesson(l.id, s)
        onChange()
    }
}
