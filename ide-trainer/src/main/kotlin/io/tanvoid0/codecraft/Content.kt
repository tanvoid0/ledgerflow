package io.tanvoid0.codecraft

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Curriculum content on disk: one small file per step, assembled here into the
 * typed [Curriculum] the rest of the plugin works in.
 *
 *     experiment.json     name, and which cheatsheet to show
 *     curriculum.json     project, stack, setup, plan, stages -> step file names
 *     steps/03-branches.json   one step: its tasks, and the IDE lessons it teaches
 *     progress.json       ticks (written by the plugin, never by hand)
 *
 * The split is the point: editing one task means opening one small file, not
 * scrolling a thousand-line monolith and hoping the diff stays readable. It
 * also keeps authored content and recorded progress in separate files, so a
 * content edit can never clobber a tick.
 */
object Content {

    const val CURRICULUM = "curriculum.json"
    const val PROGRESS = "progress.json"
    const val STEPS = "steps"

    /**
     * The spine as the file actually writes it: a stage lists its steps by
     * *file name*, where [Stage] holds the loaded steps themselves. Everything
     * else is shared, so it is read straight into the real types.
     */
    private data class Spine(
        val project: String? = null,
        val stack: String? = null,
        val setup: Setup? = null,
        val plan: Plan? = null,
        val stages: List<SpineStage>? = null,
    )

    private data class SpineStage(
        val letter: String? = null,
        val name: String? = null,
        val blurb: String? = null,
        val youWillLearn: List<String>? = null,
        val steps: List<String>? = null,
    )

    /**
     * The whole path: the spine, with every stage's step files loaded in order.
     * Null when the folder holds no readable `curriculum.json` — which is what
     * makes a folder an experiment.
     *
     * Each step is told its stage here, because the step file has no business
     * repeating something the spine already says.
     */
    fun load(dir: Path): Curriculum? {
        val text = runCatching { Files.readString(dir.resolve(CURRICULUM)) }.getOrNull() ?: return null
        val spine = runCatching { Json.gson.fromJson(text, Spine::class.java) }.getOrNull() ?: return null
        val stages = spine.stages.orEmpty().map { sg ->
            val steps = sg.steps.orEmpty()
                .mapNotNull { step(stepFile(dir, it)) }
                .onEach { it.stage = sg.letter }
            Stage(sg.letter, sg.name, sg.blurb, sg.youWillLearn, steps)
        }
        return Curriculum(spine.project, spine.stack, spine.setup, stages, spine.plan)
    }

    private fun step(file: Path): Step? {
        if (!Files.isRegularFile(file)) return null
        return runCatching { Json.gson.fromJson(Files.readString(file), Step::class.java) }.getOrNull()
    }

    /**
     * Every IDE lesson the step files declare, each stamped with the step it
     * came from — the step file already says which step it is, so a lesson
     * inside it has no business repeating that and getting it wrong.
     */
    fun lessons(dir: Path): List<Lesson> = stepFiles(dir).flatMap { (id, file) ->
        val obj = read(file) ?: return@flatMap emptyList()
        val arr = obj.getAsJsonArray("lessons") ?: return@flatMap emptyList()
        arr.mapNotNull { runCatching { Json.gson.fromJson(it, Lesson::class.java).copy(step = id) }.getOrNull() }
    }

    /**
     * Step id to the file that holds it, in curriculum order. Read as a tree
     * rather than through [load]: the callers are the ones that rewrite a step
     * file, and they need the path, not the parsed step.
     */
    fun stepFiles(dir: Path): Map<String, Path> {
        val spine = read(dir.resolve(CURRICULUM)) ?: return emptyMap()
        val out = LinkedHashMap<String, Path>()
        spine.getAsJsonArray("stages")?.forEach { st ->
            st.asJsonObject.getAsJsonArray("steps")?.forEach { f ->
                val name = runCatching { f.asString }.getOrNull() ?: return@forEach
                val file = stepFile(dir, name)
                read(file)?.get("id")?.asString?.let { out[it] = file }
            }
        }
        return out
    }

    /** A stage lists `"03-branches.json"`; `"steps/03-branches.json"` is accepted too. */
    private fun stepFile(dir: Path, name: String): Path =
        if (name.contains('/')) dir.resolve(name) else dir.resolve(STEPS).resolve(name)

    fun read(file: Path): JsonObject? {
        if (!Files.isRegularFile(file)) return null
        return runCatching { Json.parse(Files.readString(file)) }.getOrNull()
    }
}
