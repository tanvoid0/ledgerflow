package io.tanvoid0.codecraft

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The database and `progress.json` both hold the ticks, and either can be
 * written while the other is not looking — a `git checkout` rewinds the file,
 * the plugin writes both. Getting the merge wrong silently loses work, so each
 * branch of it is pinned here.
 */
class StoreDbSyncTest : BasePlatformTestCase() {

    private lateinit var dir: Path
    private lateinit var dbPath: Path

    private val t0 = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("trainer-sync")
        dbPath = dir.resolve("trainer.db")
        // Keyless tasks on purpose: the fallback key is the position, which is
        // what a hand-written step with no keys yet still has to do.
        writeExperiment(
            dir,
            """{ "id": "01", "title": "One", "tasks": [ {"do": "a"}, {"do": "b"} ] }""",
            """{ "id": "02", "title": "Two", "tasks": [ {"do": "c"}, {"do": "d"} ] }"""
        )
    }

    /** Two steps of two tasks: enough for "started but not finished" to exist. */
    private fun writeBoard(ticks: Set<String>, updatedAt: Long) =
        writeProgress(dir, ticks, Instant.ofEpochMilli(updatedAt).toString())

    private fun store(): CurriculumStore =
        CurriculumStore(project, dir, testRootDisposable, dbPath).also {
            assertTrue("could not load $dir", it.load())
        }

    private fun dbTicks(): Set<String> = ProgressDb.open(dbPath)!!.use { it.ticks() }

    private fun ticksOf(s: CurriculumStore): Set<String> =
        s.steps.flatMap { st -> st.taskList.indices.filter { s.isDone(st, it) }.map { Progress.key(st, it) } }.toSet()

    /** First run: the file is the only truth there is, so it seeds the database. */
    fun testFirstRunSeedsTheDatabaseFromTheFile() {
        writeBoard(setOf("01:0"), t0)

        val s = store()

        assertEquals(setOf("01:0"), ticksOf(s))
        assertEquals(setOf("01:0"), dbTicks())
    }

    /** The file was written after the database last was: the file wins. */
    fun testAFileWrittenLaterWins() {
        ProgressDb.open(dbPath)!!.use { it.setTick("01:0", true, t0) }
        writeBoard(setOf("01:1", "02:0"), t0 + 60_000)

        val s = store()

        assertEquals(setOf("01:1", "02:0"), ticksOf(s))
        assertEquals("the database has to adopt the file's set", setOf("01:1", "02:0"), dbTicks())
    }

    /** The file was rewound (a checkout, a stale copy): the database restores it. */
    fun testAnOlderFileLosesToTheDatabase() {
        ProgressDb.open(dbPath)!!.use {
            it.setTick("01:0", true, t0)
            it.setTick("01:1", true, t0)
        }
        writeBoard(setOf("01:0"), t0 - 60_000)

        val s = store()

        assertEquals(setOf("01:0", "01:1"), ticksOf(s))
    }

    /**
     * The file's clock must not run ahead of the database's. save() lands a
     * quarter second after the tick that scheduled it, and a progress.json
     * that is always the newer of the two wins reconcile() on every load -
     * which then deletes from the database every tick the file could not
     * express (a task since renamed, a step no longer in curriculum.json).
     */
    fun testSaveDoesNotStampTheFileAheadOfTheDatabase() {
        writeBoard(emptySet(), t0)
        val s = store()

        s.setDone(s.steps.first(), 0, true)
        s.save()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val recorded = Json.parse(Files.readString(dir.resolve(Content.PROGRESS)))!!
        val fileAt = Instant.parse(recorded.getAsJsonObject("progress").get("updatedAt").asString).toEpochMilli()
        val dbAt = ProgressDb.open(dbPath)!!.use { it.lastWriteAt() }!!
        assertTrue("file at $fileAt must not be newer than database at $dbAt", fileAt <= dbAt)
    }

    /**
     * A tick whose task the step files no longer have cannot be written to
     * `progress.json`, so a file that wins the merge must not read its own
     * silence as "undone". Renaming a task key is an ordinary content edit and
     * must not delete work from the database.
     */
    fun testAFileWinDoesNotDeleteATickItCouldNotWrite() {
        ProgressDb.open(dbPath)!!.use {
            it.setTick("01:0", true, t0)
            it.setTick("09:since-renamed", true, t0)
        }
        writeBoard(setOf("01:0"), t0 + 60_000)

        store()

        assertEquals(setOf("01:0", "09:since-renamed"), dbTicks())
    }

    fun testTickingWritesThroughToTheDatabase() {
        writeBoard(emptySet(), t0)
        val s = store()

        s.setDone(s.steps.first(), 1, true)

        assertEquals(setOf("01:1"), dbTicks())
    }

    /** Reopening lands on the step last worked in, not the first unfinished one. */
    fun testResumeStepIsTheStepLastTouched() {
        writeBoard(setOf("02:0"), t0)
        val s = store()

        assertEquals("01", s.currentStep()?.id)
        assertEquals("02", s.resumeStep()?.id)
    }

    /** A finished step is not somewhere to resume; the curriculum moves on. */
    fun testResumeSkipsAStepThatIsFinished() {
        writeBoard(setOf("02:0", "02:1"), t0)

        val s = store()

        assertEquals("01", s.resumeStep()?.id)
    }

    // ---- streak ------------------------------------------------------------
    //
    // streakDays() reads against the real clock (LocalDate.now()), not t0, so
    // these tick at noon of real days rather than a fixed instant - midnight
    // is the one time of day a tick could land on the wrong side of "today".

    private fun noon(daysAgo: Int): Long =
        LocalDate.now().minusDays(daysAgo.toLong())
            .atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun testStreakDaysIsZeroWithNoTicks() {
        writeBoard(emptySet(), t0)
        val s = store()

        assertEquals(0, s.streakDays())
    }

    /** Ticked today only: a streak of one, not zero. */
    fun testStreakDaysCountsTodayAlone() {
        writeBoard(emptySet(), t0)
        val s = store()

        s.setDone(s.steps.first(), 0, true)

        assertEquals(1, s.streakDays())
    }

    /** Three days running, ending today. */
    fun testStreakDaysCountsConsecutiveDaysEndingToday() {
        writeBoard(emptySet(), t0)
        val db = ProgressDb.open(dbPath)!!
        db.setTick("01:0", true, noon(2))
        db.setTick("01:1", true, noon(1))
        db.setTick("02:0", true, noon(0))
        db.close()

        val s = store()

        assertEquals(3, s.streakDays())
    }

    /** A day skipped breaks the streak, even with older ticks still in the database. */
    fun testStreakDaysStopsAtAGap() {
        writeBoard(emptySet(), t0)
        val db = ProgressDb.open(dbPath)!!
        db.setTick("01:0", true, noon(5))
        db.setTick("01:1", true, noon(1))
        db.setTick("02:0", true, noon(0))
        db.close()

        val s = store()

        assertEquals(2, s.streakDays())
    }

    /** Nothing ticked today: yesterday's run does not carry the streak forward. */
    fun testStreakDaysIsZeroWhenTodayIsMissed() {
        writeBoard(emptySet(), t0)
        val db = ProgressDb.open(dbPath)!!
        db.setTick("01:0", true, noon(2))
        db.setTick("01:1", true, noon(1))
        db.close()

        val s = store()

        assertEquals(0, s.streakDays())
    }
}
