package io.tanvoid0.codecraft

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.tanvoid0.codecraft.ui.StepView
import java.nio.file.Files
import java.nio.file.Path

/**
 * Alt+D is the whole loop of working through a step — tick, land on the next
 * task, and say when there is nothing left so the caller can move on. Its own
 * experiment folder in a temp directory, because this one does tick.
 */
class StepAdvanceTest : BasePlatformTestCase() {

    private lateinit var dir: Path
    private lateinit var dbPath: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("trainer-advance")
        dbPath = dir.resolve("trainer.db")
        writeExperiment(
            dir,
            """{ "id": "01", "title": "One", "tasks": [ {"key":"a","do":"a"}, {"key":"b","do":"b"} ] }"""
        )
    }

    fun testTicksEachTaskInTurnThenReportsTheStepIsDone() {
        val store = CurriculumStore(project, dir, testRootDisposable, dbPath)
        assertTrue("could not load $dir", store.load())
        val step = store.steps.single()
        val view = StepView(project, Ide(project))
        view.show(store, step)

        assertTrue(view.tickCurrentAndAdvance())
        assertTrue("the first task is the one you are on", store.isDone(step, 0))
        assertFalse("and only that one", store.isDone(step, 1))

        assertTrue(view.tickCurrentAndAdvance())
        assertTrue(store.isComplete(step))

        assertFalse("nothing left to tick says so, so the caller can move step", view.tickCurrentAndAdvance())
    }
}
