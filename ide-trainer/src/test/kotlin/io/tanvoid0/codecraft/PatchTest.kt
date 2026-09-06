package io.tanvoid0.codecraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patching is the only thing in the plugin that writes into a file the learner
 * already has work in, and the line numbers come from a model — so the rules
 * that decide what a model's answer is allowed to do are worth more tests than
 * anything else here.
 */
class PatchTest {

    private val pom = """
        <project>
          <dependencies>
            <dependency>
              <artifactId>spring-boot-starter-web</artifactId>
            </dependency>
          </dependencies>
        </project>
    """.trimIndent()

    private val snippet = "<dependency>\n  <artifactId>flyway-core</artifactId>\n</dependency>"

    private fun edit(json: String, lines: Int = pom.lines().size, snippetLines: Int = 3) =
        Patch.parse(json, lines, snippetLines)

    // ---- what the model is allowed to say ---------------------------------

    @Test fun insertIsStartEqualsEnd() {
        val e = edit("""{"start": 6, "end": 6, "indent": "    ", "reason": "inside dependencies"}""")
        assertEquals(6, e.start)
        assertEquals(0, e.replaces)
        assertEquals("    ", e.indent)
    }

    @Test fun missingEndMeansInsert() {
        assertEquals(0, edit("""{"start": 2}""").replaces)
    }

    @Test fun proseAroundTheJsonIsIgnored() {
        val e = edit("Sure! ```json\n{\"start\": 2, \"end\": 2}\n```\nHope that helps.")
        assertEquals(2, e.start)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aLineBeyondTheFileIsRefused() = edit("""{"start": 400, "end": 400}""").let { }

    @Test(expected = IllegalArgumentException::class)
    fun endBeforeStartIsRefused() = edit("""{"start": 5, "end": 2}""").let { }

    @Test(expected = IllegalArgumentException::class)
    fun swallowingTheFileIsRefused() =
        // Three lines of snippet must not eat sixty lines of someone's file.
        edit("""{"start": 1, "end": 61}""", lines = 200).let { }

    @Test(expected = IllegalStateException::class)
    fun noJsonAtAllIsRefused() = edit("I think it goes near the top").let { }

    @Test fun anIndentThatIsNotWhitespaceIsDropped() {
        // The indent is written in front of real code, so "code" in that field
        // would be the model smuggling in a line it invented.
        assertEquals("", edit("""{"start": 2, "indent": "int x = 1;"}""").indent)
    }

    // ---- what gets written ------------------------------------------------

    @Test fun insertPutsTheSnippetVerbatimAtTheLine() {
        val out = Patch.apply(pom, snippet, Patch.Edit(6, 6, "    ", ""))
        assertEquals(
            listOf(
                "<project>",
                "  <dependencies>",
                "    <dependency>",
                "      <artifactId>spring-boot-starter-web</artifactId>",
                "    </dependency>",
                "    <dependency>",
                "      <artifactId>flyway-core</artifactId>",
                "    </dependency>",
                "  </dependencies>",
                "</project>",
            ),
            out.lines(),
        )
    }

    @Test fun replaceDropsExactlyTheNamedLines() {
        // Lines 2..6 are the whole <dependencies> element; `end` is exclusive.
        val out = Patch.apply(pom, "  <dependencies/>", Patch.Edit(2, 7, "", ""))
        assertEquals(listOf("<project>", "  <dependencies/>", "</project>"), out.lines())
    }

    @Test fun theTrailingNewlineSurvives() {
        assertTrue(Patch.apply("a\nb\n", "x", Patch.Edit(2, 2, "", "")).endsWith("x\nb\n"))
    }

    @Test fun blankSnippetLinesAreNotIndentedIntoTrailingWhitespace() {
        val out = Patch.apply("a\nb", "one\n\ntwo", Patch.Edit(2, 2, "  ", ""))
        assertEquals(listOf("a", "  one", "", "  two", "b"), out.lines())
    }

    // ---- the second click ---------------------------------------------------

    @Test fun aSnippetAlreadyInTheFileIsRecognisedWhateverItsIndent() {
        val patched = Patch.apply(pom, snippet, Patch.Edit(6, 6, "    ", ""))
        assertTrue(Patch.alreadyIn(patched, snippet))
        assertFalse(Patch.alreadyIn(pom, snippet))
    }

    @Test fun theSameLinesScatteredAboutAreNotAMatch() {
        assertFalse(Patch.alreadyIn("<dependency>\nother\n</dependency>", "<dependency>\n</dependency>"))
    }

    // ---- the prompt ----------------------------------------------------------

    @Test fun thePromptNumbersEveryLineFromOne() {
        val p = Patch.prompt("pom.xml", "alpha\nbeta", "x")
        assertTrue(p.contains("1: alpha"))
        assertTrue(p.contains("2: beta"))
        // Indentation of the instructions must survive the file being pasted in.
        assertTrue(p.startsWith("You are placing a snippet"))
    }
}
