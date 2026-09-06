package io.tanvoid0.codecraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ExperimentTest {

    @Test fun loadsEveryExperimentFolderAndTakesItsLessonsFromTheStepFiles() {
        val root = Files.createTempDirectory("experiments")
        val one = Files.createDirectory(root.resolve("alpha"))
        Files.writeString(one.resolve("experiment.json"), """{"name":"Alpha"}""")
        writeExperiment(one, """
            { "id": "01", "title": "One", "tasks": [ {"key":"a","do":"a"} ],
              "lessons": [ {"id":"l1","title":"T","teach":"go",
                            "trigger":{"type":"action","id":"GotoClass"}} ] }
        """.trimIndent())
        Files.createDirectory(root.resolve("not-an-experiment"))   // no manifest

        val found = Experiments.load(root)

        assertEquals(1, found.size)
        assertEquals("Alpha", found[0].name)
        assertEquals(one, found[0].dir)
        assertEquals("GotoClass", found[0].lessons[0].trigger.id)
        // The step file says which step it is; the lesson inside it does not repeat that.
        assertEquals("01", found[0].lessons[0].step)
    }

    /** Opening one module as its own project must still find the curriculum. */
    @Test fun baseWalksUpToTheDirectoryHoldingIdeTrainer() {
        val repo = Files.createTempDirectory("repo")
        Files.createDirectories(repo.resolve("ide-trainer/experiments"))
        val module = Files.createDirectories(repo.resolve("account-service/src"))

        assertEquals(repo, Experiments.baseFrom(module))
        assertEquals(repo, Experiments.baseFrom(repo))
    }

    @Test fun baseFallsBackToTheProjectWhenThereIsNoCurriculumAnywhereAbove() {
        val lonely = Files.createTempDirectory("lonely")
        assertEquals(lonely, Experiments.baseFrom(lonely))
    }

    @Test fun aBrokenManifestIsSkippedRatherThanKillingTheToolWindow() {
        val root = Files.createTempDirectory("experiments")
        Files.createDirectory(root.resolve("broken"))
            .resolve("experiment.json").also { Files.writeString(it, "{{{ not json") }

        assertTrue(Experiments.load(root).isEmpty())
    }

    @Test fun lessonsAreSelectedByStep() {
        val exp = Experiment().also {
            it.lessons = listOf(
                Lesson(id = "a", step = "01"), Lesson(id = "b", step = "02"), Lesson(id = "c", step = "01"))
        }
        assertEquals(listOf("a", "c"), exp.lessonsFor("01").map { it.id })
        assertTrue(exp.lessonsFor(null).isEmpty())
    }

    /**
     * Every path that ships in this repo loads, and every task in it carries a
     * key. Content is hand-edited (and agent-edited) far more often than this
     * code changes, so the guard belongs on the content.
     */
    @Test fun everyShippedExperimentLoadsAndIsFullyKeyed() {
        val root = Path.of("experiments")
        val found = Experiments.load(root)
        assertTrue("no experiments found under $root", found.isNotEmpty())
        found.forEach { exp ->
            val steps = Content.load(exp.dir).allSteps
            assertTrue("${exp.name} has no steps", steps.isNotEmpty())
            steps.forEach { st ->
                val keys = st.taskList.map { it.key }
                assertTrue("${exp.name} step ${st.id} has a keyless task", keys.all { !it.isNullOrBlank() })
                assertEquals("${exp.name} step ${st.id} reuses a key", keys.size, keys.toSet().size)
            }
            // Ids are the other half of a tick key.
            val ids = steps.map { it.id }
            assertEquals("${exp.name} reuses a step id", ids.size, ids.toSet().size)
        }
    }
}
