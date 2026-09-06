package io.tanvoid0.codecraft

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * An experiment is a folder under `ide-trainer/experiments/` holding one
 * `experiment.json`, a `curriculum.json` spine, a `steps/` folder and the
 * `progress.json` the plugin writes. See `Content` for the layout.
 *
 * The manifest itself stays tiny on purpose: everything that gets edited
 * often lives in the per-step files, where a change touches one small file.
 */
data class Trigger(val type: String = "", val id: String = "")

data class Lesson(
    val id: String = "",
    val step: String = "",
    val title: String = "",
    val teach: String = "",
    val trigger: Trigger = Trigger(),
    val fallbackTip: String = "",
)

data class Experiment(
    val name: String = "",
    /** Quick-command reference, relative to the experiment folder. Optional. */
    val cheatsheet: String = "",
) {
    /** Set after loading — the folder the manifest came from. */
    @Transient var dir: Path = Path.of(".")

    /** Set after loading — collected from the step files, not from the manifest. */
    @Transient var lessons: List<Lesson> = emptyList()

    fun lessonsFor(step: String?) = lessons.filter { it.step == step }
}

/**
 * Does an observed IDE event satisfy a lesson's trigger?
 *
 * `id` is an exact match for action / executor / tool-window ids, `*` for "any
 * event of this kind" (a breakpoint of any type, say), `*.ext` for a file
 * extension, and a path suffix otherwise — so a `fileOpen` lesson can name
 * `src/main/resources/application.yml` without knowing the absolute path.
 */
fun triggerMatches(t: Trigger, kind: String, value: String): Boolean {
    if (t.type != kind || t.id.isEmpty()) return false
    val v = value.replace('\\', '/')
    return when {
        t.id == "*" -> true
        t.id.startsWith("*.") -> v.endsWith(t.id.substring(1))
        else -> v == t.id || v.endsWith("/" + t.id)
    }
}

object Experiments {
    private val gson = Gson()

    /**
     * The directory `ide-trainer/` sits in. Usually the open project, but a
     * learner may open one module (`ledgerflow/account-service`) as its own
     * project, and the curriculum still lives up the tree - so walk up to the
     * first ancestor that has one, and fall back to the project itself.
     */
    fun baseFrom(start: Path): Path =
        generateSequence(start) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("ide-trainer/experiments")) }
            ?: start

    fun base(project: Project): Path? = project.basePath?.let { baseFrom(Path.of(it)) }

    fun root(project: Project): Path? = base(project)?.resolve("ide-trainer/experiments")

    fun load(project: Project): List<Experiment> =
        root(project)?.let { load(it) }.orEmpty()

    fun load(root: Path): List<Experiment> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.sorted().toList().mapNotNull { dir ->
                val manifest = dir.resolve("experiment.json")
                if (!Files.isRegularFile(manifest)) return@mapNotNull null
                runCatching {
                    gson.fromJson(Files.readString(manifest), Experiment::class.java).also {
                        it.dir = dir
                        it.lessons = Content.lessons(dir)
                    }
                }.getOrNull()
            }
        }
    }
}
