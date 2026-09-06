package io.tanvoid0.codecraft

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The count behind `edit_file`'s refuse-unless-unique rule (ADR-002). This is
 * the safety property of the edit tool: undercount and the agent edits an
 * occurrence the user never looked at, overcount and a perfectly clear edit is
 * refused. Cheap to get subtly wrong, so pinned.
 */
class EditMatchTest {

    @Test fun findsASingleMatch() = assertEquals(1, countMatches("val a = 1\nval b = 2", "val b = 2"))

    @Test fun countsEveryOccurrence() = assertEquals(3, countMatches("x\nx\nx", "x"))

    @Test fun missingNeedleIsZero() = assertEquals(0, countMatches("abc", "zzz"))

    /**
     * The one a `split`-based count gets wrong: matches must not overlap, so
     * "aa" occurs once in "aaa", not twice.
     */
    @Test fun overlappingMatchesAreNotDoubleCounted() = assertEquals(1, countMatches("aaa", "aa"))

    @Test fun fourAsHoldTwoPairs() = assertEquals(2, countMatches("aaaa", "aa"))

    /** An empty needle matches everywhere and nowhere; refusing is the safe read. */
    @Test fun emptyNeedleIsZero() = assertEquals(0, countMatches("abc", ""))

    /** Multi-line fragments are the normal case for a real edit. */
    @Test fun countsMultiLineFragments() =
        assertEquals(2, countMatches("if (x) {\n  go()\n}\nif (x) {\n  go()\n}", "if (x) {\n  go()\n}"))
}
