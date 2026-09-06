package io.tanvoid0.codecraft

import io.tanvoid0.codecraft.editorPreamble
import io.tanvoid0.codecraft.mentionRefs
import io.tanvoid0.codecraft.planSlug
import io.tanvoid0.codecraft.ui.rankMentions
import io.tanvoid0.codecraft.rulesPreamble
import io.tanvoid0.codecraft.trimHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure bits behind "@ a file" and Plan mode. Both decide what lands on
 * disk or in the prompt, so both are worth a check: a mention parsed wrong
 * silently sends no context, and a slug parsed wrong writes a junk filename.
 */
class ChatContextTest {

    @Test fun picksEveryMentionOnce() =
        assertEquals(
            listOf("src/Wallet.java", "README.md"),
            mentionRefs("compare @src/Wallet.java with @README.md and @src/Wallet.java again"),
        )

    @Test fun noMentionsIsEmpty() = assertEquals(emptyList<String>(), mentionRefs("just a question"))

    @Test fun mentionStopsAtWhitespace() =
        assertEquals(listOf("a/b.kt"), mentionRefs("look at @a/b.kt please"))

    @Test fun slugIsFilenameSafe() =
        assertEquals("add-a-wallet-endpoint", planSlug("Add a wallet endpoint!"))

    @Test fun slugIsCapped() =
        assertEquals(40, planSlug("a".repeat(30) + " " + "b".repeat(30)).length)

    /** The 40-char cut landing exactly on the dash must not leave it trailing. */
    @Test fun cutOnASeparatorLeavesNoTrailingDash() =
        assertEquals("a".repeat(39), planSlug("a".repeat(39) + " " + "b".repeat(5)))

    @Test fun punctuationOnlyPromptStillNamesAFile() = assertEquals("plan", planSlug("?!?"))

    private val paths = listOf(
        "src/main/java/io/ledgerflow/account/domain/model/Wallet.java",
        "Wallet.java",
        "README.md",
        "src/test/WalletTest.java",
    )

    /** Enter takes the top match, so which path sorts first is the whole UX. */
    @Test fun shortestMatchRanksFirst() =
        assertEquals("Wallet.java", rankMentions(paths, "Wallet").first())

    @Test fun nonMatchesAreDropped() =
        assertEquals(listOf("README.md"), rankMentions(paths, "READ"))

    @Test fun bareAtOffersEverything() =
        assertEquals(paths.size, rankMentions(paths, "").size)

    @Test fun limitIsHonoured() = assertEquals(2, rankMentions(paths, "", limit = 2).size)

    // ---- history budget --------------------------------------------------

    private fun turn(role: String, text: String) = role to text

    @Test fun shortHistoryIsUntouched() {
        val all = listOf(turn("user", "a"), turn("assistant", "b"))
        assertEquals(all, trimHistory(all, budget = 100))
    }

    /** Oldest turns go first - they are the ones the model would lose anyway. */
    @Test fun oldestTurnsAreDroppedFirst() {
        val all = listOf(turn("user", "x".repeat(60)), turn("assistant", "y".repeat(20)), turn("user", "z"))

        val kept = trimHistory(all, budget = 40)

        assertEquals(listOf("y".repeat(20), "z"), kept.map { it.second })
    }

    /**
     * The question actually being asked must survive however long it is -
     * trimming it away would send an empty prompt.
     */
    @Test fun newestTurnSurvivesEvenWhenItAloneExceedsBudget() {
        val all = listOf(turn("user", "old"), turn("user", "q".repeat(500)))

        val kept = trimHistory(all, budget = 10)

        assertEquals(listOf("q".repeat(500)), kept.map { it.second })
    }

    @Test fun emptyHistoryStaysEmpty() = assertEquals(emptyList<Pair<String, String>>(), trimHistory(emptyList()))

    // ---- project rules ---------------------------------------------------

    @Test fun rulesAreReadFromTheFirstFileThatHasThem() {
        val text = rulesPreamble({ name -> if (name == "CLAUDE.md") "Use the Bash tool." else null })

        assertTrue(text.contains("CLAUDE.md"))
        assertTrue(text.contains("Use the Bash tool."))
    }

    /** The plugin's own rules file wins over a generic one. */
    @Test fun theMostSpecificRulesFileWins() {
        val text = rulesPreamble({ name ->
            when (name) {
                "ide-trainer/RULES.md" -> "specific"
                "CLAUDE.md" -> "generic"
                else -> null
            }
        })

        assertTrue(text.contains("specific"))
        assertTrue(!text.contains("generic"))
    }

    /** A file that exists but is empty must fall through, not win with nothing. */
    @Test fun aBlankRulesFileFallsThrough() {
        val text = rulesPreamble({ name ->
            when (name) {
                "ide-trainer/RULES.md" -> "   "
                "CLAUDE.md" -> "real rules"
                else -> null
            }
        })

        assertTrue(text.contains("real rules"))
    }

    @Test fun noRulesFileMeansNoPreamble() = assertEquals("", rulesPreamble({ null }))

    // ---- editor context --------------------------------------------------

    /** No editor open is the ordinary case in a fresh project - say nothing, not "null". */
    @Test fun noOpenFileMeansNoPreamble() = assertEquals("", editorPreamble(null, 1, emptyList()))

    @Test fun openFileAndCaretAreNamed() {
        val text = editorPreamble("src/Wallet.java", 42, emptyList())

        assertTrue(text.contains("src/Wallet.java"))
        assertTrue("the caret line is the point of it: $text", text.contains("42"))
    }

    /** A clean file must not claim problems it does not have. */
    @Test fun aCleanFileReportsNoProblems() =
        assertTrue(!editorPreamble("A.kt", 1, emptyList()).contains("reports"))

    /**
     * The diagnostics are the part worth sending - without them the model asks
     * for a build to learn what the editor already had underlined.
     */
    @Test fun problemsAreListed() {
        val text = editorPreamble("A.kt", 3, listOf("line 3 ERROR: unresolved reference: foo"))

        assertTrue(text.contains("unresolved reference: foo"))
        assertTrue("each problem on its own line: $text", text.contains("- line 3 ERROR"))
    }

    /** A rules file that grew into a manual must not crowd out the conversation. */
    @Test fun oversizedRulesAreCapped() {
        val text = rulesPreamble({ "r".repeat(9_000) }, limit = 100)

        // The body is cut to the limit and no further. Counting every "r" in
        // the whole string would count the header's own letters too.
        assertTrue(text.contains("r".repeat(100)))
        assertTrue("body must stop at the limit", !text.contains("r".repeat(101)))
    }
}
