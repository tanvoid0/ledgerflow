package io.tanvoid0.codecraft

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The database is the one place progress is not guessed: it knows what was
 * ticked *and when*. These check the reads the UI depends on — the tick set,
 * the step you last touched, what you got done today — and the merge the
 * store makes when `progress.json` holds the same ticks.
 */
class ProgressDbTest : TestCase() {

    private lateinit var dir: Path
    private lateinit var db: ProgressDb

    private val t0 = 1_700_000_000_000L

    override fun setUp() {
        dir = Files.createTempDirectory("trainer-db")
        db = ProgressDb.open(dir.resolve("trainer.db")) ?: error("could not open db")
    }

    override fun tearDown() {
        db.close()
    }

    fun testTicksRoundTrip() {
        db.setTick("01:0", true, t0)
        db.setTick("01:1", true, t0 + 1)

        assertEquals(setOf("01:0", "01:1"), db.ticks())
    }

    fun testUntickRemovesTheTick() {
        db.setTick("01:0", true, t0)
        db.setTick("01:0", false, t0 + 1)

        assertTrue(db.ticks().isEmpty())
    }

    /** An empty database has no last write, which is how "first run" is detected. */
    fun testLastWriteIsNullUntilSomethingHappens() {
        assertNull(db.lastWriteAt())

        db.setTick("01:0", true, t0)

        assertEquals(t0, db.lastWriteAt())
    }

    /** Unticking still counts as a write, or the JSON would win the next merge. */
    fun testUntickCountsAsAWrite() {
        db.setTick("01:0", true, t0)
        db.setTick("01:0", false, t0 + 5)

        assertEquals(t0 + 5, db.lastWriteAt())
    }

    fun testStartedStepAtIsTheFirstTickOfThatStep() {
        db.setTick("02:0", true, t0 + 10)
        db.setTick("01:0", true, t0)
        db.setTick("01:1", true, t0 + 20)

        assertEquals(t0, db.startedStepAt("01"))
        assertNull(db.startedStepAt("03"))
    }

    /** Reopening picks the step you were last in, not the first unfinished one. */
    fun testLastTouchedStep() {
        db.setTick("01:0", true, t0)
        db.setTick("07:2", true, t0 + 1)

        assertEquals("07", db.lastTouchedStep())
    }

    fun testTicksSinceCountsOnlyTicksAfterTheCutoff() {
        db.setTick("01:0", true, t0)
        db.setTick("01:1", true, t0 + 100)
        db.setTick("01:1", false, t0 + 200)

        assertEquals(1, db.ticksSince(t0 + 50))
    }

    fun testReplaceTicksAdoptsAnotherWritersSet() {
        db.setTick("01:0", true, t0)
        db.setTick("01:1", true, t0)

        db.replaceTicks(setOf("01:1", "02:0"), t0 + 500)

        assertEquals(setOf("01:1", "02:0"), db.ticks())
        assertEquals(t0 + 500, db.lastWriteAt())
    }

    fun testLessonStateRoundTrips() {
        db.setLesson("goto", LessonState.DONE, t0)
        db.setLesson("annotate", LessonState.SKIPPED, t0)
        db.setLesson("goto", LessonState.SKIPPED, t0 + 1)

        assertEquals(
            mapOf("goto" to LessonState.SKIPPED, "annotate" to LessonState.SKIPPED),
            db.lessons()
        )
    }

    /** Setup checks are remembered so five tools are not re-verified every session. */
    fun testCheckResultsRoundTrip() {
        db.setCheck("Docker", true, t0)
        db.setCheck("psql", false, t0 + 1)
        db.setCheck("Docker", false, t0 + 2)

        val checks = db.checks()
        assertEquals(ProgressDb.Check(false, t0 + 2), checks["Docker"])
        assertEquals(ProgressDb.Check(false, t0 + 1), checks["psql"])
        assertNull(checks["nope"])
    }

    /** A check is not a tick: it must survive the JSON winning a merge and replacing the tick set. */
    fun testChecksSurviveATickReplacement() {
        db.setCheck("Docker", true, t0)

        db.replaceTicks(setOf("01:0"), t0 + 100)

        assertEquals(true, db.checks()["Docker"]?.ok)
    }

    /** The file is the point: a new connection over it sees the same state. */
    fun testStateSurvivesReopening() {
        db.setTick("01:0", true, t0)
        db.setLesson("goto", LessonState.DONE, t0)
        db.setCheck("Docker", true, t0)
        db.close()

        val again = ProgressDb.open(dir.resolve("trainer.db")) ?: error("could not reopen db")
        try {
            assertEquals(setOf("01:0"), again.ticks())
            assertEquals(LessonState.DONE, again.lessons()["goto"])
            assertEquals(true, again.checks()["Docker"]?.ok)
        } finally {
            again.close()
            db = again
        }
    }
}
