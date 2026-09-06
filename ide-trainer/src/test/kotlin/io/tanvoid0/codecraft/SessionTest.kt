package io.tanvoid0.codecraft

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tool window used to keep this state in four hand-written refresh methods,
 * where the risk was never the drawing but the sequencing: which panels are
 * told what, in what order, and — the one with real teeth — whether a tick is
 * reported as *just* completing a step, since that is what pops the
 * "step complete" notification. Once is right; twice is noise and never is a
 * silent regression.
 */
class SessionTest : BasePlatformTestCase() {

    private lateinit var dir: Path
    private lateinit var events: MutableList<SessionEvent>

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("trainer-session")
        events = mutableListOf()
    }

    private fun experimentAt(path: Path) = Experiment(name = "T").also { it.dir = path }

    private fun session(): Session =
        Session(project, testRootDisposable).also { it.subscribe { e -> events.add(e) } }

    private fun twoTaskStep() = writeExperiment(
        dir,
        """{ "id": "01", "title": "One", "tasks": [ {"key":"a","do":"a"}, {"key":"b","do":"b"} ] }"""
    )

    fun testOpeningAPathAnnouncesItThenTheStepToWorkOn() {
        twoTaskStep()
        val session = session()

        session.open(experimentAt(dir))

        val opened = events.filterIsInstance<SessionEvent.Opened>().single()
        assertNull("a path that loads has no failure to show", opened.failure)
        val selected = events.filterIsInstance<SessionEvent.Selected>().single()
        assertEquals("01", selected.step?.id)
        assertFalse("a step restored on open must not steal the caret", selected.focus)
        assertEquals("01", session.selected?.id)
        // The path is announced before the step, or the panels render a step
        // against the previous path's store.
        assertTrue(events.indexOf(opened) < events.indexOf(selected))
    }

    /** A folder with no curriculum.json is the ordinary "not an experiment yet" case. */
    fun testAPathThatCannotLoadReportsWhyAndBlanksTheStep() {
        val empty = Files.createTempDirectory("trainer-empty")
        val session = session()

        session.open(experimentAt(empty))

        val opened = events.filterIsInstance<SessionEvent.Opened>().single()
        assertNotNull("a path that fails must say so", opened.failure)
        assertTrue(opened.failure!!.contains("curriculum.json"))
        assertNull("nothing is being worked on", session.store)
        assertNull(events.filterIsInstance<SessionEvent.Selected>().single().step)
    }

    fun testTickingANonFinalTaskReportsNoCompletion() {
        twoTaskStep()
        val session = session()
        session.open(experimentAt(dir))
        val step = session.store!!.steps.single()

        session.store!!.setDone(step, 0, true)

        assertNull(
            "one of two tasks is not a finished step",
            events.filterIsInstance<SessionEvent.Ticked>().single().completed,
        )
    }

    fun testFinishingTheLastTaskReportsTheStepCompleteExactlyOnce() {
        twoTaskStep()
        val session = session()
        session.open(experimentAt(dir))
        val store = session.store!!
        val step = store.steps.single()

        store.setDone(step, 0, true)
        store.setDone(step, 1, true)

        val ticks = events.filterIsInstance<SessionEvent.Ticked>()
        assertEquals(2, ticks.size)
        assertNull(ticks[0].completed)
        assertEquals("the step closed on the second tick", "01", ticks[1].completed?.id)

        // Re-ticking a task that is already done must not announce it again -
        // the notification is for the moment it closes, not for every event
        // afterwards.
        store.setDone(step, 1, true)
        assertNull(events.filterIsInstance<SessionEvent.Ticked>().last().completed)
    }

    /** Unticking reopens the step, so finishing it again is a fresh completion. */
    fun testReopeningAStepLetsItCompleteAgain() {
        twoTaskStep()
        val session = session()
        session.open(experimentAt(dir))
        val store = session.store!!
        val step = store.steps.single()
        store.setDone(step, 0, true)
        store.setDone(step, 1, true)

        store.setDone(step, 1, false)
        store.setDone(step, 1, true)

        assertEquals("01", events.filterIsInstance<SessionEvent.Ticked>().last().completed?.id)
    }

    fun testStepAtWalksTheCurriculumAndStopsAtTheEnds() {
        writeExperiment(
            dir,
            """{ "id": "01", "title": "One", "tasks": [ {"key":"a","do":"a"} ] }""",
            """{ "id": "02", "title": "Two", "tasks": [ {"key":"a","do":"a"} ] }""",
        )
        val session = session()
        session.open(experimentAt(dir))

        assertEquals("01", session.selected?.id)
        assertNull("nothing before the first step", session.stepAt(-1))
        assertEquals("02", session.stepAt(1)?.id)

        session.select(session.stepAt(1))

        assertEquals("01", session.stepAt(-1)?.id)
        assertNull("nothing after the last step", session.stepAt(1))
    }

    /** A deliberate move takes the caret; a restore does not. */
    fun testSelectingWithFocusSaysSo() {
        twoTaskStep()
        val session = session()
        session.open(experimentAt(dir))

        session.select(session.selected, focus = true)

        assertTrue(events.filterIsInstance<SessionEvent.Selected>().last().focus)
    }
}
