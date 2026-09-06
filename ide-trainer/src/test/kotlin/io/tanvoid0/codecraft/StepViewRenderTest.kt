package io.tanvoid0.codecraft

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import io.tanvoid0.codecraft.ui.SetupView
import io.tanvoid0.codecraft.ui.StepView
import io.tanvoid0.codecraft.ui.StepsTree
import java.nio.file.Path
import javax.swing.tree.TreePath

/**
 * "Does it render" is a test, not something a human confirms by opening the
 * tool window: every step of the real curriculum, with its real tasks, blocks
 * and editors.
 *
 * Read-only throughout — nothing here ticks a task, so the real experiment's
 * `progress.json` is never written.
 */
class StepViewRenderTest : BasePlatformTestCase() {

    private fun realStore(): CurriculumStore {
        val dir = Path.of("experiments", "ledgerflow").toAbsolutePath().normalize()
        val store = CurriculumStore(project, dir, testRootDisposable)
        assertTrue("could not load $dir", store.load())
        return store
    }

    fun testEveryStepOfTheRealCurriculumBuilds() {
        val store = realStore()
        val view = StepView(project, Ide(project))
        assertEquals(21, store.steps.size)
        store.steps.forEach { step ->
            view.show(store, step)
            assertTrue("step ${step.id} rendered nothing", view.componentCount > 0)
        }
    }

    /**
     * Four of the six shipped paths are bare checklists — a task is a line of
     * text, with no blocks, checks or lessons. That is a supported shape, not a
     * broken one, and it renders through exactly the same panel; this builds
     * every step of every path to keep it that way.
     */
    fun testEveryShippedPathBuilds() {
        val view = StepView(project, Ide(project))
        val setup = SetupView(project, Ide(project))
        Experiments.load(Path.of("experiments")).forEach { exp ->
            val store = CurriculumStore(project, exp.dir, testRootDisposable)
            assertTrue("could not load ${exp.name}", store.load())
            assertTrue("${exp.name} has no steps", store.steps.isNotEmpty())
            store.steps.forEach { step ->
                view.show(store, step)
                assertTrue("${exp.name} step ${step.id} rendered nothing", view.componentCount > 0)
            }
            // A path with no setup section shows the empty state, not nothing.
            setup.show(store)
            assertTrue("${exp.name} setup tab rendered nothing", setup.componentCount > 0)
        }
    }

    fun testTheStepsTreeBuildsAndSelects() {
        val store = realStore()
        var picked: Step? = null
        val tree = StepsTree { picked = it }
        tree.show(store)
        tree.refresh(store.currentStep())
        assertNull("selecting programmatically must not fire the callback", picked)
    }

    /**
     * A dock eight rows tall must not show five expanded stages, and moving to
     * a step in another stage is what re-folds them.
     */
    fun testTheTreeKeepsOneStageOpen() {
        val store = realStore()
        val tree = StepsTree { }
        tree.show(store)
        val jtree = UIUtil.findComponentOfType(tree, Tree::class.java)!!
        tree.refresh(store.steps.first())
        assertEquals("only the first stage stays open", 1, expandedStages(jtree))
        tree.refresh(store.steps.last())
        assertEquals("moving stage re-folds the rest", 1, expandedStages(jtree))
        assertTrue("the step it moved to must be visible", jtree.isVisible(jtree.selectionPath))
    }

    private fun expandedStages(jtree: Tree): Int =
        (0 until jtree.model.getChildCount(jtree.model.root)).count { i ->
            jtree.isExpanded(TreePath(arrayOf(jtree.model.root, jtree.model.getChild(jtree.model.root, i))))
        }

    fun testTheSetupTabBuilds() {
        val view = SetupView(project, Ide(project))
        view.show(realStore())
        assertTrue(view.componentCount > 0)
    }

    /** With nothing loaded the panels say so rather than throwing. */
    fun testEmptyStateIsHandled() {
        StepView(project, Ide(project)).show(null, null)
        StepsTree { }.show(null)
        SetupView(project, Ide(project)).show(null)
    }
}
