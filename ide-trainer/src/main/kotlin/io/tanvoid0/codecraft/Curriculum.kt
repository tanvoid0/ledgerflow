package io.tanvoid0.codecraft

import com.google.gson.annotations.SerializedName

/**
 * A typed view of the `curriculum` half of the board's data file.
 *
 * Every field is nullable on purpose. Gson allocates these with Unsafe and
 * never runs the Kotlin constructor, so a declared default is a lie for any
 * key the file happens to omit — a non-null `String` would simply be null and
 * blow up at the first use site instead of here.
 */
data class Curriculum(
    val project: String? = null,
    val stack: String? = null,
    val setup: Setup? = null,
    val stages: List<Stage>? = null,
    val plan: Plan? = null,
)

data class Stage(
    val letter: String? = null,
    val name: String? = null,
    val blurb: String? = null,
    val youWillLearn: List<String>? = null,
    val steps: List<Step>? = null,
)

data class Step(
    val id: String? = null,
    val title: String? = null,
    val estimate: String? = null,
    val goal: String? = null,
    val startWith: String? = null,
    val newIdeas: List<Idea>? = null,
    val tasks: List<Task>? = null,
    val proof: Proof? = null,
    val doneWhen: String? = null,
    val services: List<String>? = null,
    val infra: List<String>? = null,
    val newThisStep: String? = null,
    /** Project-relative folder every command in this step runs from. Null = project root. */
    val dir: String? = null,
) {
    /** Filled in after parsing; the stage letter a step belongs to. */
    @Transient var stage: String? = null

    val taskList: List<Task> get() = tasks.orEmpty()
}

/** Every step of every stage, in curriculum order — the list the plugin works in. */
val Curriculum?.allSteps: List<Step> get() = this?.stages.orEmpty().flatMap { it.steps.orEmpty() }

data class Idea(val term: String? = null, val plain: String? = null)

data class Task(
    /**
     * Stable identity for progress. Ticks are stored as `stepId:key`, so a
     * task keeps its tick when the prose around it is edited, when tasks are
     * reordered, and when new ones are inserted above it. Unique within the
     * step; never reused for different work.
     */
    val key: String? = null,
    // "do" is a Kotlin keyword, and the field has to keep the file's name.
    @SerializedName("do") val action: String? = null,
    val why: String? = null,
    val how: String? = null,
    val blocks: List<Block>? = null,
    val check: Check? = null,
    /** Overrides the step's dir for this task. "" means the project root. */
    val dir: String? = null,
)

/** kind is one of shell / file / paste / note. All four carry code. */
data class Block(val label: String? = null, val kind: String? = null, val body: String? = null)

data class Check(val run: String? = null, val expect: String? = null)

data class Proof(val run: String? = null, val expect: String? = null, val note: String? = null)

data class Setup(
    val intro: String? = null,
    val shell: String? = null,
    val tools: List<Tool>? = null,
    val notes: List<Note>? = null,
)

data class Tool(
    val name: String? = null,
    val why: String? = null,
    val check: String? = null,
    val install: String? = null,
)

data class Note(val h: String? = null, val b: String? = null)

/** Only the parts of the plan the progress block is built from. */
data class Plan(val milestones: List<Milestone>? = null)

data class Milestone(
    val id: String? = null,
    val name: String? = null,
    val steps: List<String>? = null,
    val extras: List<Extra>? = null,
)

data class Extra(val id: String? = null)

private val TAGS = Regex("<[^>]*>")
private val DASH = Regex("\\s[\u2014\u2013-]\\s")
private val PARENS = Regex("\\s*\\(.*$")
private val PREFIX = Regex("^[A-Za-z][\\w.-]*:\\s+")
private val PATH = Regex("^[\\w.@+-]+(/[\\w.@+-]+)*\\.[A-Za-z0-9]+$")

/**
 * The file a block's label names, if it names one.
 *
 * Labels are written for a human ("ledger: application/PlaceHold.java - the use
 * case"), so strip the aside, the prefix and any parenthetical, then insist on
 * something that actually looks like a path. Anything else is prose and gets
 * no "open" button. Same rules as the board's `labelPath()`, and the same nine
 * cases are tested.
 */
fun labelPath(label: String): String? {
    val s = label.replace(TAGS, "")
        .split(DASH)[0].split(", ")[0]
        .replace(PARENS, "")
        .replace(PREFIX, "")
        .trim()
    return if (PATH.matches(s)) s else null
}

private val PACKAGE = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)

/**
 * Where a Java block's file actually goes.
 *
 * Most labels are written package-relative ("adapter/in/web/Handler.java"),
 * because that is how a human refers to the class — but Maven wants the whole
 * `src/main/java/io/ledgerflow/...` prefix, and taking the label literally
 * drops the file at the project root with a folder tree beside it that no
 * source root knows about. The body declares its own package, so derive the
 * path from that and keep only the file name from the label.
 *
 * Tests go under `src/test/java`: the label says so, or the class name does.
 * A label that already carries the full path derives the same answer, so it
 * survives unchanged. Non-Java, or Java with no package line, is returned as
 * written — nothing better is known about it.
 */
fun sourcePath(label: String, body: String?): String? {
    val path = labelPath(label) ?: return null
    if (!path.endsWith(".java")) return path
    val pkg = body?.let { PACKAGE.find(it) }?.groupValues?.get(1) ?: return path
    val name = path.substringAfterLast('/')
    val test = path.startsWith("src/test/") || name.removeSuffix(".java").let {
        it.endsWith("Test") || it.endsWith("Tests") || it.endsWith("IT")
    }
    val root = if (test) "src/test/java" else "src/main/java"
    return "$root/${pkg.replace('.', '/')}/$name"
}

/**
 * Some checks are an instruction to look at something ("compare the two
 * counts", "watch the notification-service terminal") rather than a command.
 * Running one proves nothing and exits 0, which would tick the task on no
 * evidence at all, so they are shown as a note instead.
 */
fun isObservation(run: String) =
    run.lines().all { it.isBlank() || it.trimStart().startsWith("#") }

