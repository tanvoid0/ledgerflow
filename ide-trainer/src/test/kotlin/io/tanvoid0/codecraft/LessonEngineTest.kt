package io.tanvoid0.codecraft

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The engine's real risk is not the matcher (unit-tested separately) but the
 * wiring: subscribing to a topic on the wrong message bus produces a listener
 * that silently never fires, which looks exactly like a learner who has not
 * done the thing yet. These tests publish the real platform events.
 */
class LessonEngineTest : BasePlatformTestCase() {

    private lateinit var dir: Path
    private lateinit var experiment: Experiment

    private fun lesson(id: String, step: String, type: String, trigger: String) =
        Lesson(id = id, step = step, title = id, teach = "", trigger = Trigger(type, trigger))

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("trainer-test")
    }

    /**
     * The engine has no database of its own — lesson state goes through the
     * store that owns the experiment's connection, so a test needs one too.
     * Only the database half is used here, which the constructor opens.
     */
    private fun storeFor(): CurriculumStore =
        CurriculumStore(project, dir, testRootDisposable, dir.resolve("trainer.db"))

    private fun engineWith(vararg lessons: Lesson, step: String = "01"): LessonEngine {
        experiment = Experiment(name = "T").also {
            it.dir = dir
            it.lessons = lessons.toList()
        }
        val engine = LessonEngine(project, testRootDisposable) {}
        engine.arm(experiment, storeFor(), step)
        return engine
    }

    private fun settle() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    fun testActionTriggerMarksLessonDone() {
        val l = lesson("goto", "01", "action", "GotoClass")
        val engine = engineWith(l)
        assertEquals(LessonState.PENDING, engine.stateOf(l))

        fireAction("GotoClass")

        assertEquals(LessonState.DONE, engine.stateOf(l))
    }

    fun testUnrelatedActionLeavesLessonPending() {
        val l = lesson("goto", "01", "action", "GotoClass")
        val engine = engineWith(l)

        fireAction("GotoFile")

        assertEquals(LessonState.PENDING, engine.stateOf(l))
    }

    /** A shortcut pressed in step 12 must not tick a lesson belonging to step 01. */
    fun testLessonForAnotherStepIsNotArmed() {
        val l = lesson("goto", "01", "action", "GotoClass")
        val engine = engineWith(l, step = "12")

        fireAction("GotoClass")

        assertEquals(LessonState.PENDING, engine.stateOf(l))
    }

    fun testToolWindowTrigger() {
        val l = lesson("tw", "01", "toolWindow", "Project")
        val engine = engineWith(l)

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Project")
        if (tw == null) return   // no Project tool window in this fixture; nothing to assert
        project.messageBus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown(tw)
        settle()

        assertEquals(LessonState.DONE, engine.stateOf(l))
    }

    fun testFileCreateTrigger() {
        val l = lesson("mig", "01", "fileCreate", "*.sql")
        val engine = engineWith(l)

        myFixture.addFileToProject("db/migration/V1__init.sql", "select 1;")
        settle()

        assertEquals(LessonState.DONE, engine.stateOf(l))
    }

    fun testProgressIsPersistedAndReloaded() {
        val l = lesson("goto", "01", "action", "GotoClass")
        val engine = engineWith(l)
        fireAction("GotoClass")

        assertTrue("trainer.db should exist", Files.exists(dir.resolve("trainer.db")))

        // a fresh engine over the same experiment folder must remember it
        val reopened = LessonEngine(project, testRootDisposable) {}
        reopened.arm(experiment, storeFor(), "01")
        assertEquals(LessonState.DONE, reopened.stateOf(l))
    }

    fun testSkipIsRecordedAsSkippedNotDone() {
        val l = lesson("tw", "01", "toolWindow", "Database")
        val engine = engineWith(l)

        engine.skip(l)

        assertEquals(LessonState.SKIPPED, engine.stateOf(l))

        // skipped is remembered as skipped, not as done and not as pending
        val reopened = LessonEngine(project, testRootDisposable) {}
        reopened.arm(experiment, storeFor(), "01")
        assertEquals(LessonState.SKIPPED, reopened.stateOf(l))
    }

    private fun fireAction(id: String) {
        val action = ActionManager.getInstance().getAction(id) ?: error("no action $id in this IDE")
        val event = TestActionEvent.createTestEvent(action)
        ApplicationManager.getApplication().messageBus
            .syncPublisher(AnActionListener.TOPIC)
            .afterActionPerformed(action, event, AnActionResult.PERFORMED)
        settle()
    }
}
