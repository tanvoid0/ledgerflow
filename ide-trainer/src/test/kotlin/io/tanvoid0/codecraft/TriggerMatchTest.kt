package io.tanvoid0.codecraft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one branchy bit of the engine: a wrong match ticks a lesson nobody did. */
class TriggerMatchTest {
    private fun t(type: String, id: String) = Trigger(type, id)

    @Test fun exactIdMatches() =
        assertTrue(triggerMatches(t("action", "GotoClass"), "action", "GotoClass"))

    @Test fun wrongKindNeverMatches() =
        assertFalse(triggerMatches(t("action", "Run"), "run", "Run"))

    @Test fun otherActionDoesNotMatch() =
        assertFalse(triggerMatches(t("action", "GotoClass"), "action", "GotoFile"))

    @Test fun extensionGlobMatchesAnyPath() =
        assertTrue(triggerMatches(t("fileOpen", "*.http"), "fileOpen", "D:/p/api/calls.http"))

    @Test fun extensionGlobRejectsOtherExtensions() =
        assertFalse(triggerMatches(t("fileOpen", "*.http"), "fileOpen", "D:/p/api/calls.json"))

    @Test fun pathSuffixMatchesOnWindowsSeparators() =
        assertTrue(triggerMatches(t("fileOpen", "resources/application.yml"), "fileOpen",
            "D:\\p\\svc\\src\\main\\resources\\application.yml"))

    /** "…/notes.yml" must not satisfy a trigger for "…/application.yml". */
    @Test fun suffixMustStartAtASegmentBoundary() =
        assertFalse(triggerMatches(t("fileOpen", "application.yml"), "fileOpen", "D:/p/myapplication.yml"))

    @Test fun emptyTriggerIdMatchesNothing() =
        assertFalse(triggerMatches(t("action", ""), "action", "GotoClass"))

    @Test fun starMatchesAnyValueOfThatKind() =
        assertTrue(triggerMatches(t("breakpoint", "*"), "breakpoint", "java-line"))

    @Test fun starStillRespectsKind() =
        assertFalse(triggerMatches(t("breakpoint", "*"), "fileCreate", "java-line"))
}
