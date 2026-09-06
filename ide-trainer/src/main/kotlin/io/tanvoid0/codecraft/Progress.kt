package io.tanvoid0.codecraft

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

/**
 * The one Gson this plugin uses, and the one place a string becomes a tree.
 *
 * Two-space indent, and HTML escaping off — by default Gson replaces every
 * angle bracket in the curriculum's `<code>` tags with a unicode escape, which
 * parses back fine and turns the file into something no one can read or diff.
 * Both settings only affect writing, so the same instance reads too.
 */
object Json {

    val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun parse(text: String): JsonObject? =
        runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
}

/**
 * Progress as derived data: every field of `progress.json` recomputes from the
 * step files plus the tick set, and nothing here knows about the IDE, the
 * filesystem or the EDT.
 *
 * That is what `ProgressCompatibilityTest` pins — rebuild the block from a real
 * path's own ticks and it must come out identical — so a content edit can never
 * quietly change what a tick means.
 */
object Progress {

    /**
     * The tick key for a task: its own `key` when it has one, its position
     * when it does not. A keyless task is the old positional behaviour, and
     * editing around it moves its tick — which is why the authoring rule is
     * that every task gets a key.
     */
    fun key(step: Step, i: Int) = "${step.id}:${step.taskList.getOrNull(i)?.key ?: i}"

    fun doneCount(step: Step, ticks: Set<String>) =
        step.taskList.indices.count { ticks.contains(key(step, it)) }

    /**
     * The ticks `progress.json` holds: `progress.steps[].tasks[]` naming the
     * done task keys, plus `progress.plan.extrasDone` for the plan's extras, which the
     * plugin never sets but must not lose.
     */
    fun ticksFrom(root: JsonObject): MutableSet<String> {
        val out = HashSet<String>()
        val progress = root.getAsJsonObject("progress") ?: return out
        progress.getAsJsonArray("steps")?.forEach { el ->
            val st = el.asJsonObject
            val id = st.get("id")?.asString ?: return@forEach
            st.getAsJsonArray("tasks")?.forEach { k ->
                runCatching { k.asString }.getOrNull()?.let { out.add("$id:$it") }
            }
        }
        progress.getAsJsonObject("plan")?.getAsJsonArray("extrasDone")?.forEach {
            out.add("x:${it.asString}")
        }
        return out
    }

    /** 1-based index of the first step with an unticked task; the last step once all are done. */
    fun reached(steps: List<Step>, ticks: Set<String>): Int {
        steps.forEachIndexed { i, st -> if (doneCount(st, ticks) < st.taskList.size) return i + 1 }
        return steps.size
    }

    /** The derived view of a tick set: counts, the current step, and the plan rollup. */
    fun build(steps: List<Step>, plan: Plan?, ticks: Set<String>, now: String): JsonObject {
        val stepsArr = JsonArray()
        var done = 0
        var complete = 0
        var total = 0

        steps.forEach { st ->
            // Keys, not a positional flag array: the whole point of the key is
            // that a tick survives the task list being edited around it.
            val doneKeys = JsonArray()
            var d = 0
            st.taskList.indices.forEach { i ->
                val k = key(st, i)
                if (ticks.contains(k)) {
                    doneKeys.add(k.substringAfter(':'))
                    d++
                }
            }
            done += d
            total += st.taskList.size
            val full = d == st.taskList.size
            if (full) complete++
            stepsArr.add(JsonObject().apply {
                addProperty("id", st.id)
                addProperty("title", st.title)
                addProperty("done", d)
                addProperty("total", st.taskList.size)
                addProperty("complete", full)
                add("tasks", doneKeys)
            })
        }

        val r = reached(steps, ticks)
        val at = steps.getOrNull(r - 1)

        return JsonObject().apply {
            addProperty("updatedAt", now)
            add("summary", JsonObject().apply {
                addProperty("tasksDone", done)
                addProperty("tasksTotal", total)
                addProperty("percent", if (total > 0) Math.round(done.toDouble() / total * 100).toInt() else 0)
                addProperty("stepsComplete", complete)
                addProperty("stepsTotal", steps.size)
                addProperty("currentStep", at?.id)
                addProperty("currentStepTitle", at?.title)
            })
            add("steps", stepsArr)
            if (plan != null) add("plan", buildPlan(plan, steps, ticks))
        }
    }

    private fun buildPlan(plan: Plan, steps: List<Step>, ticks: Set<String>): JsonObject {
        val extras = JsonArray()
        plan.milestones.orEmpty().forEach { m ->
            m.extras.orEmpty().forEach { x -> if (ticks.contains("x:${x.id}")) extras.add(x.id) }
        }
        val milestones = JsonArray()
        plan.milestones.orEmpty().forEach { m ->
            var done = 0
            var total = 0
            m.steps.orEmpty().forEach { id ->
                val st = steps.firstOrNull { it.id == id } ?: return@forEach
                done += doneCount(st, ticks)
                total += st.taskList.size
            }
            m.extras.orEmpty().forEach { x ->
                total++
                if (ticks.contains("x:${x.id}")) done++
            }
            milestones.add(JsonObject().apply {
                addProperty("id", m.id)
                addProperty("name", m.name)
                addProperty("done", done)
                addProperty("total", total)
                addProperty("complete", total > 0 && done == total)
            })
        }
        return JsonObject().apply {
            add("extrasDone", extras)
            add("milestones", milestones)
        }
    }

    /** The whole of `progress.json`: recorded state, and nothing authored. */
    fun document(steps: List<Step>, plan: Plan?, ticks: Set<String>, ui: JsonObject, now: String): JsonObject =
        JsonObject().apply {
            add("progress", build(steps, plan, ticks, now))
            add("ui", ui)
        }

    fun now(): String = Instant.now().toString()
}
