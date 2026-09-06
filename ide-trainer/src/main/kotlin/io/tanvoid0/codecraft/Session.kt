package io.tanvoid0.codecraft

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * What changed, for whoever is drawing it. The tool window renders each of
 * these in one `when`, so the compiler is what insists every kind of change
 * reaches every panel that cares — rather than four hand-written refresh
 * methods that each have to remember the same list.
 */
sealed interface SessionEvent {

    /**
     * A path was opened, or failed to. Everything on screen is stale, including
     * the panels that only change when the path does. [failure] is the reason
     * to show instead of content, or null when it loaded.
     */
    data class Opened(val failure: String?) : SessionEvent

    /** Content was re-read from disk — a hand-edited step, or a rewound `progress.json`. */
    object Reloaded : SessionEvent

    /**
     * A different step is being worked on. [focus] is the difference between a
     * step the learner asked for and one restored under them: only a deliberate
     * move may take the caret.
     */
    data class Selected(val step: Step?, val focus: Boolean) : SessionEvent

    /** A task was ticked. Counts moved; [completed] is the step that just closed, if one did. */
    data class Ticked(val completed: Step?) : SessionEvent
}

/**
 * The tool window's state, without any of its Swing: which paths exist, which
 * one is open, its store, and the step being worked on.
 *
 * Everything that used to be a field on the tool window plus four refresh
 * methods lives here as one small state machine that says what changed and
 * lets the panels answer for themselves.
 */
class Session(private val project: Project, private val parent: Disposable) {

    var experiments: List<Experiment> = Experiments.load(project)
        private set
    var experiment: Experiment? = default()
        private set
    var store: CurriculumStore? = null
        private set
    var selected: Step? = null
        private set

    /** Whether [selected] was already finished, so a tick can tell when it *just* finished. */
    private var wasComplete = false

    private val listeners = mutableListOf<(SessionEvent) -> Unit>()

    fun subscribe(listener: (SessionEvent) -> Unit) {
        listeners.add(listener)
    }

    private fun fire(event: SessionEvent) = listeners.forEach { it(event) }

    /**
     * Opens a path: closes whatever was open (saving a tick still sitting in the
     * debounce), builds its store, and lands on the step to resume.
     */
    fun open(exp: Experiment?) {
        store?.save()
        store?.close()
        experiment = exp
        store = exp?.let { CurriculumStore(project, it.dir, parent, it.dir.resolve("trainer.db")) }

        if (store?.load() != true) {
            store = null
            selected = null
            fire(SessionEvent.Opened(failureFor(exp)))
            fire(SessionEvent.Selected(null, focus = false))
            return
        }
        store?.onChange { tick() }
        fire(SessionEvent.Opened(null))
        select(store?.resumeStep())
    }

    private fun failureFor(exp: Experiment?) =
        if (exp == null) "No experiments found. Add one under ide-trainer/experiments/."
        else "Could not read ${exp.dir.fileName}. It needs a curriculum.json and a steps/ folder."

    fun select(step: Step?, focus: Boolean = false) {
        selected = step
        wasComplete = step != null && store?.isComplete(step) == true
        fire(SessionEvent.Selected(step, focus))
    }

    /** A task was ticked; the store already wrote it. */
    private fun tick() {
        val st = selected
        val complete = st != null && store?.isComplete(st) == true
        val justCompleted = if (complete && !wasComplete) st else null
        wasComplete = complete
        fire(SessionEvent.Ticked(justCompleted))
    }

    /**
     * Something under the experiment folder changed on disk. Keeps the step
     * being worked on when it still exists, because a hand-edit elsewhere in
     * the path is no reason to move the learner.
     */
    fun reloadFromDisk() {
        if (store?.reloadIfChangedOnDisk() != true) return
        val id = selected?.id
        fire(SessionEvent.Reloaded)
        select(store?.stepById(id) ?: store?.currentStep())
    }

    /** The gear's Reload: re-scan the folder for paths, then reopen the one that was open. */
    fun reload() {
        experiments = Experiments.load(project)
        open(experiments.firstOrNull { it.name == experiment?.name } ?: default())
    }

    /**
     * The path to open when nothing has been picked yet. LedgerFlow is the
     * one built around a real project, so it is the one a first run should
     * land on rather than whichever folder sorts first.
     */
    private fun default() =
        experiments.firstOrNull { it.dir.fileName.toString() == DEFAULT_PATH } ?: experiments.firstOrNull()

    /** The step [offset] places from the selected one, or null at either end. */
    fun stepAt(offset: Int): Step? {
        val steps = store?.steps.orEmpty()
        val i = steps.indexOfFirst { it === selected }
        return if (i < 0) null else steps.getOrNull(i + offset)
    }

    fun save() = store?.save()

    companion object {
        const val DEFAULT_PATH = "ledgerflow"
    }
}
