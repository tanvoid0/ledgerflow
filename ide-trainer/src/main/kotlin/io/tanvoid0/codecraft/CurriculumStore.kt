package io.tanvoid0.codecraft

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.Alarm
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One experiment folder: the curriculum assembled from its step files, the
 * ticks the learner has made, and a debounced write-back of `progress.json`
 * through the VFS so the IDE never reports the file as changed behind its back.
 *
 * Only `progress.json` is ever written. Authored content is read-only to the
 * plugin, which is what makes hand-editing a step file safe at any time.
 */
class CurriculumStore(
    private val project: Project,
    private val dir: Path,
    parent: Disposable,
    dbPath: Path? = null,
) {

    /**
     * Progress also goes into a database, which is the half of it the JSON
     * cannot hold: when each tick happened. Null means no database (the tests
     * that only care about rendering), and everything still works off the
     * file alone.
     */
    private val db: ProgressDb? = dbPath?.let { ProgressDb.open(it) }

    private val path: Path = dir.resolve(Content.PROGRESS)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private val listeners = mutableListOf<() -> Unit>()

    private var ui: JsonObject = JsonObject()
    private val ticks = HashSet<String>()
    private var lastWritten: String? = null
    private var dirty = false

    var curriculum: Curriculum? = null
        private set
    var steps: List<Step> = emptyList()
        private set

    val loaded: Boolean get() = curriculum != null

    init {
        Disposer.register(parent) { close() }
    }

    /** Called on an experiment switch too; the Disposer then closes an already-closed connection, which is a no-op. */
    fun close() {
        db?.close()
    }

    fun load(): Boolean {
        val content = Content.load(dir) ?: return false
        // No progress.json yet is a fresh start, not a failure: authored
        // content is what makes an experiment loadable.
        val recorded = Content.read(path) ?: JsonObject()

        curriculum = content
        steps = content.allSteps
        val fileTicks = Progress.ticksFrom(recorded)
        ticks.clear()
        ticks.addAll(fileTicks)
        ui = recorded.getAsJsonObject("ui")?.deepCopy() ?: JsonObject()
        dirty = false
        reconcile(recorded, fileTicks)
        return true
    }

    /**
     * Two writers, one truth. The plugin writes both `progress.json` and the
     * database, and a `git checkout` can wind the file back on its own — so
     * whoever wrote last wins and the other side is brought up to date, with
     * `progress.updatedAt` and the database's newest event as the two clocks.
     *
     * An empty database is not a conflict, it is a first run: the file seeds
     * it. That is what makes adopting this a no-op for progress already made.
     */
    private fun reconcile(root: JsonObject, fileTicks: Set<String>) {
        val db = db ?: return
        val dbAt = db.lastWriteAt()
        if (dbAt == null) {
            db.replaceTicks(fileTicks, System.currentTimeMillis())
            return
        }
        // A tick whose task is no longer in the step files cannot be written to
        // `progress.json` at all, so the file's silence about it is not a claim
        // that it was undone. Those stay out of the comparison and out of any
        // set the file wins with - otherwise renaming one task key deletes its
        // tick from the database the next time the plugin loads.
        val stored = db.ticks()
        val writable = writableKeys()
        val hidden = stored.filterNotTo(HashSet()) { it in writable }
        val visible = stored - hidden

        val fileAt = updatedAt(root)
        if (fileAt != null && fileAt > dbAt) {
            db.replaceTicks(fileTicks + hidden, fileAt)
            return
        }
        if (visible == fileTicks) return
        ticks.clear()
        ticks.addAll(visible)
        dirty = true
        scheduleSave()
    }

    /**
     * Every tick key the content as it stands can express: one per task in the
     * step files, plus the plan's extras. A key outside this set cannot appear
     * in `progress.json` however much the learner has done, so the file is no
     * evidence either way about it.
     */
    private fun writableKeys(): Set<String> {
        val out = HashSet<String>()
        steps.forEach { st -> st.taskList.indices.forEach { out.add(Progress.key(st, it)) } }
        curriculum?.plan?.milestones.orEmpty().forEach { m ->
            m.extras.orEmpty().forEach { out.add("x:${it.id}") }
        }
        return out
    }

    /** Parsed, not string-compared: two writers print different fraction digits. */
    private fun updatedAt(root: JsonObject): Long? = runCatching {
        Instant.parse(root.getAsJsonObject("progress").get("updatedAt").asString).toEpochMilli()
    }.getOrNull()

    /** Something under the folder changed — a hand-edited step, or a rewound `progress.json`. */
    fun reloadIfChangedOnDisk(): Boolean {
        val text = runCatching { Files.readString(path) }.getOrNull()
        if (text != null && text == lastWritten) return false
        return load()
    }

    fun onChange(l: () -> Unit) {
        listeners.add(l)
    }

    private fun fire() = listeners.forEach { it() }

    fun ticksSnapshot(): Set<String> = ticks.toSet()

    fun isDone(step: Step, i: Int) = ticks.contains(Progress.key(step, i))

    fun doneCount(step: Step) = Progress.doneCount(step, ticks)

    fun isComplete(step: Step) = step.taskList.isNotEmpty() && doneCount(step) == step.taskList.size

    fun setDone(step: Step, i: Int, value: Boolean) {
        val k = Progress.key(step, i)
        if (value) ticks.add(k) else ticks.remove(k)
        db?.setTick(k, value, System.currentTimeMillis())
        dirty = true
        scheduleSave()
        fire()
    }

    /** First step with an unticked task. */
    fun currentStep(): Step? = steps.getOrNull(Progress.reached(steps, ticks) - 1)

    /**
     * Where to reopen: the step last worked in, unless it is finished.
     * "First unfinished step" is the wrong place for someone who deliberately
     * jumped ahead.
     */
    fun resumeStep(): Step? = lastTouchedStep()?.takeIf { !isComplete(it) } ?: currentStep()

    fun stepById(id: String?): Step? = steps.firstOrNull { it.id == id }

    fun totals(): Pair<Int, Int> =
        steps.sumOf { doneCount(it) } to steps.sumOf { it.taskList.size }

    // ---- what only the database knows ------------------------------------

    /** When the first task of this step was ticked, or null if it has not started. */
    fun startedStepAt(step: Step): Long? = db?.startedStepAt(step.id.orEmpty())

    /** Tasks ticked since midnight, local time. */
    fun ticksToday(): Int =
        db?.ticksSince(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) ?: 0

    /** Consecutive local days, ending today, with at least one tick. 0 if nothing was ticked today. */
    fun streakDays(): Int {
        val zone = ZoneId.systemDefault()
        val days = db?.doneTimestamps().orEmpty()
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .toHashSet()
        var d = LocalDate.now()
        var streak = 0
        while (days.contains(d)) {
            streak++
            d = d.minusDays(1)
        }
        return streak
    }

    /** Every lesson this experiment has ever settled, straight from the database. */
    fun lessonStates(): Map<String, LessonState> = db?.lessons().orEmpty()

    /** Settled by the lesson engine, which has no connection of its own. */
    fun setLesson(id: String, state: LessonState) =
        db?.setLesson(id, state, System.currentTimeMillis())

    /** What a setup check last said, or null if it has never been run here. */
    fun check(name: String): ProgressDb.Check? = db?.checks()?.get(name)

    fun setCheck(name: String, ok: Boolean, reason: String? = null) =
        db?.setCheck(name, ok, System.currentTimeMillis(), reason)

    /** The step last worked in, which is a better place to reopen than the first unfinished one. */
    fun lastTouchedStep(): Step? = db?.lastTouchedStep()?.let { id -> steps.firstOrNull { it.id == id } }

    private fun scheduleSave() {
        alarm.cancelAllRequests()
        alarm.addRequest({ save() }, 250)
    }

    /**
     * Nothing to write unless a tick changed something. Closing the tool window
     * should not rewrite `updatedAt` and reformat a file nobody touched.
     */
    fun save() {
        if (!dirty) return
        if (curriculum == null) return
        val p = path
        // Stamped with the database's clock, not `now`. save() lands a quarter
        // second after the tick that scheduled it, so `now` makes the file
        // permanently the newer of the two and reconcile() hands it the win on
        // every single load - which then deletes from the database every tick
        // progress.json could not express, because buildProgress only emits
        // ticks whose keys still exist in the step files.
        val stamp = db?.lastWriteAt()?.let { Instant.ofEpochMilli(it).toString() } ?: Progress.now()
        val text = Json.gson.toJson(
            Progress.document(steps, curriculum?.plan, ticks, ui, stamp)
        )
        lastWritten = text
        dirty = false
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                // First tick of a fresh path: the VFS has nothing to find yet.
                if (!Files.isRegularFile(p)) Files.writeString(p, text)
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p) ?: return@runCatching
                WriteCommandAction.runWriteCommandAction(project) {
                    vf.setBinaryContent(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
}
