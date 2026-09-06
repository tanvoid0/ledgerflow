package io.tanvoid0.codecraft

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.tanvoid0.codecraft.ui.MapView
import io.tanvoid0.codecraft.ui.RoadMetrics
import io.tanvoid0.codecraft.ui.arrivals
import io.tanvoid0.codecraft.ui.estimateDays
import io.tanvoid0.codecraft.ui.layoutRoadmap
import io.tanvoid0.codecraft.ui.systemPerStep
import java.nio.file.Path

/**
 * The map is derived, not authored: the data file has no dependency field, so
 * the links between steps come out of what each step says it runs on. Get that
 * derivation wrong and the graph is confidently misleading, which is worse
 * than no graph — hence the arithmetic here is pinned against the real
 * curriculum rather than a fixture.
 *
 * Read-only throughout: nothing ticks a task, so the real experiment's
 * `progress.json` is never written.
 */
class RoadmapTest : BasePlatformTestCase() {

    private fun realStore(): CurriculumStore {
        val dir = Path.of("experiments", "ledgerflow").toAbsolutePath().normalize()
        val store = CurriculumStore(project, dir, testRootDisposable)
        assertTrue("could not load $dir", store.load())
        return store
    }

    private fun metrics() = RoadMetrics(
        pad = 10, gutter = 16, laneGap = 12, nodeGap = 6, headerH = 90,
        laneHeadH = 24, rowH = 16, smallH = 12, nodePad = 6, barH = 4, minLane = 260,
    )

    fun testEstimatesTurnIntoDays() {
        assertEquals(0.5, estimateDays("half a day"), 0.0)
        assertEquals(1.0, estimateDays("1 day"), 0.0)
        assertEquals(3.0, estimateDays("3 days"), 0.0)
        assertEquals(10.0, estimateDays("2 weeks"), 0.0)
        assertEquals(0.0, estimateDays(null), 0.0)
        assertEquals(0.0, estimateDays("as long as it takes"), 0.0)
    }

    /** "+ balance-service" means one more, not "only this one". */
    fun testTheSystemAccumulatesAndNothingEverLeaves() {
        val steps = realStore().steps
        val system = systemPerStep(steps)
        assertEquals(listOf("account-service"), system["01"])
        assertTrue(system["02"].orEmpty().containsAll(listOf("account-service", "postgres")))
        assertTrue(
            system["13"].orEmpty().containsAll(
                listOf("account-service", "postgres", "redpanda", "schema registry", "balance-service", "redis")
            )
        )
        steps.zipWithNext { a, b ->
            assertTrue(
                "step ${b.id} dropped something step ${a.id} was running on",
                system[b.id].orEmpty().containsAll(system[a.id].orEmpty())
            )
        }
    }

    fun testEveryPartOfTheSystemHasAStepItArrivesIn() {
        val first = arrivals(realStore().steps)
        assertEquals("01", first["account-service"])
        assertEquals("02", first["postgres"])
        assertEquals("04", first["ledger-service"])
        assertEquals("07", first["redpanda"])
        assertEquals("13", first["redis"])
    }

    fun testTheLayoutStacksEveryStepInOneColumnAtDockWidth() {
        val store = realStore()
        val map = layoutRoadmap(
            store.curriculum?.stages.orEmpty(), metrics(), 300, { store.doneCount(it) }, { 0 to 0 }
        )
        assertEquals(21, map.nodes.size)
        assertEquals(5, map.lanes.size)
        map.nodes.zipWithNext { a, b ->
            assertTrue(
                "node ${b.step.id} overlaps ${a.step.id}",
                b.bounds.y >= a.bounds.y + a.bounds.height
            )
        }
        val last = map.nodes.last().bounds
        assertTrue("the canvas is shorter than its own content", map.height >= last.y + last.height)
    }

    fun testAWideDockLaysTheLanesOutSideBySide() {
        val store = realStore()
        val map = layoutRoadmap(
            store.curriculum?.stages.orEmpty(), metrics(), 1400, { store.doneCount(it) }, { 0 to 0 }
        )
        assertEquals(5, map.lanes.map { it.bounds.x }.distinct().size)
        assertEquals(21, map.nodes.size)
    }

    /** The links the file never states: step 13 still runs on what 01, 02 and 07 brought in. */
    fun testAStepDependsOnTheStepsThatBroughtInWhatItRunsOn() {
        val store = realStore()
        val map = layoutRoadmap(
            store.curriculum?.stages.orEmpty(), metrics(), 300, { store.doneCount(it) }, { 0 to 0 }
        )
        assertTrue(map.dependsOn["13"].orEmpty().containsAll(listOf("01", "02", "07")))
        assertFalse("a step cannot depend on itself", map.dependsOn["01"].orEmpty().contains("01"))
        assertTrue("the first step depends on nothing", map.dependsOn["01"].orEmpty().isEmpty())
        assertEquals(listOf("balance-service", "redis"), map.node("13")?.arriving)
    }

    fun testTheMatrixHasARowPerPartOfTheSystem() {
        val store = realStore()
        val map = layoutRoadmap(
            store.curriculum?.stages.orEmpty(), metrics(), 900, { store.doneCount(it) }, { 0 to 0 }
        )
        assertNotNull("the matrix should fit at 900px", map.matrix)
        val mx = map.matrix!!
        assertEquals(arrivals(store.steps).size, mx.rows.size)
        assertEquals("account-service", mx.rows.first().name)
        assertEquals("13", mx.rows.first { it.name == "redis" }.arrivesAt)
        mx.rows.zipWithNext { a, b -> assertEquals(mx.rowH, b.y - a.y) }
        assertTrue("columns start after the labels", mx.cellX(0) >= mx.x + mx.labelW)
        assertTrue("the last column runs off the canvas", mx.cellX(map.nodes.size) <= 900)
        val last = mx.rows.last()
        assertTrue("the canvas is shorter than the matrix", map.height >= last.y + mx.rowH)
    }

    /** A cell three pixels wide says nothing; the section is dropped instead. */
    fun testTheMatrixIsDroppedWhenThereIsNoRoomForACell() {
        val store = realStore()
        val map = layoutRoadmap(
            store.curriculum?.stages.orEmpty(), metrics(), 120, { store.doneCount(it) }, { 0 to 0 }
        )
        assertNull(map.matrix)
        assertEquals(21, map.nodes.size)
    }

    fun testTheMapViewBuildsAndHandlesNothingLoaded() {
        val store = realStore()
        val view = MapView { }
        view.show(store, emptyList()) { emptyMap() }
        assertEquals(21, view.map()?.nodes?.size)
        view.show(null, emptyList()) { emptyMap() }
        assertNull(view.map())
    }
}
