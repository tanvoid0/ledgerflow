package io.tanvoid0.codecraft

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Progress is derived: every number in `progress.json` recomputes from the
 * step files and the tick set. So the test is not "does it serialise", it is
 * "rebuild the progress block from the real curriculum's own ticks and compare
 * it with what is on disk" — which catches a content edit that silently moves
 * what a tick means, the failure this whole layout exists to prevent.
 */
class ProgressCompatibilityTest {

    private val dir: Path = Path.of("experiments", "ledgerflow")

    private fun content(): Curriculum {
        assertTrue("$dir should be an experiment folder", Files.isDirectory(dir.resolve(Content.STEPS)))
        return Content.load(dir)!!
    }

    private fun recorded(): JsonObject = Content.read(dir.resolve(Content.PROGRESS))!!

    @Test fun theCurriculumParses() {
        val c = content()
        assertNotNull(c)
        val steps = c.allSteps
        assertEquals(21, steps.size)
        assertEquals(106, steps.sumOf { it.taskList.size })
        // Every step knows its stage, and every task has something to do.
        assertTrue(steps.all { !it.stage.isNullOrBlank() })
        assertTrue(steps.flatMap { it.taskList }.all { !it.action.isNullOrBlank() })
    }

    /**
     * The reason ticks are keyed rather than positional. A missing key silently
     * falls back to the position, and a duplicate makes two tasks share a tick
     * — both are invisible until progress is already wrong.
     */
    @Test fun everyTaskHasItsOwnKey() {
        content().allSteps.forEach { st ->
            val keys = st.taskList.map { it.key }
            assertTrue("step ${st.id} has a keyless task", keys.all { !it.isNullOrBlank() })
            assertEquals("step ${st.id} reuses a key", keys.size, keys.toSet().size)
        }
    }

    @Test fun rebuildingProgressReproducesWhatIsOnDisk() {
        val steps = content().allSteps
        val ticks = Progress.ticksFrom(recorded())

        val theirs = recorded().getAsJsonObject("progress").deepCopy()
        val ours = Progress.build(steps, content().plan, ticks, "ignored")

        // The timestamp is the one field that is meant to differ.
        theirs.remove("updatedAt")
        ours.remove("updatedAt")

        assertEquals(theirs, ours)
    }

    @Test fun tickingATaskMovesTheCountersAndNothingElse() {
        val steps = content().allSteps
        val ticks = Progress.ticksFrom(recorded())
        val before = Progress.build(steps, null, ticks, "t").getAsJsonObject("summary")

        // Tick the first unticked task of the step the learner is on.
        val current = steps[Progress.reached(steps, ticks) - 1]
        val next = current.taskList.indices.first { !ticks.contains(Progress.key(current, it)) }
        ticks.add(Progress.key(current, next))

        val after = Progress.build(steps, null, ticks, "t").getAsJsonObject("summary")

        assertEquals(before.get("tasksDone").asInt + 1, after.get("tasksDone").asInt)
        assertEquals(before.get("tasksTotal").asInt, after.get("tasksTotal").asInt)
        assertEquals(current.id, after.get("currentStep").asString)
    }

    /** Finishing the last task of a step advances the step reported as current. */
    @Test fun finishingAStepAdvancesTheCurrentStep() {
        val steps = content().allSteps
        val ticks = Progress.ticksFrom(recorded())
        val current = steps[Progress.reached(steps, ticks) - 1]

        current.taskList.indices.forEach { ticks.add(Progress.key(current, it)) }

        val summary = Progress.build(steps, null, ticks, "t").getAsJsonObject("summary")
        val expected = steps[steps.indexOf(current) + 1]
        assertEquals(expected.id, summary.get("currentStep").asString)
    }

    /**
     * A tick names a task, so re-ordering the tasks around it must not move it.
     * This is the guarantee the whole key scheme buys, and it is one line to
     * break by going back to positions.
     */
    @Test fun aTickSurvivesItsTaskMovingWithinTheStep() {
        val steps = content().allSteps
        val step = steps.first { it.taskList.size > 2 }
        val last = step.taskList.last()
        val ticks = setOf(Progress.key(step, step.taskList.size - 1))

        val reordered = step.copy(tasks = listOf(last) + step.taskList.dropLast(1))
            .also { it.stage = step.stage }

        assertEquals(1, Progress.doneCount(reordered, ticks))
        assertTrue("the tick followed the task, not the slot", ticks.contains(Progress.key(reordered, 0)))
    }

    /** Progress holds no authored prose — that is the point of the split. */
    @Test fun theProgressDocumentIsRecordedStateOnly() {
        val steps = content().allSteps
        val out = Progress.document(steps, null, Progress.ticksFrom(recorded()), JsonObject(), "t")

        assertEquals(setOf("progress", "ui"), out.keySet())
    }

    /**
     * Gson escapes angle brackets by default, and the curriculum prose is full
     * of `<code>` tags. Escaped output parses back fine, so nothing would fail
     * except the files becoming unreadable and every diff enormous.
     */
    @Test fun stepFilesKeepTheirMarkupLiteral() {
        val text = Files.readString(Content.stepFiles(dir).values.first())
        assertTrue("angle brackets must stay literal", text.contains("<code>"))
        assertTrue("no unicode-escaped markup", !text.contains("\\u003c"))
    }
}
