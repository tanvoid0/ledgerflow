package io.tanvoid0.codecraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every match here becomes a clickable link in a lesson, so the false
 * positives matter as much as the hits: "e.g." must stay prose, and a tag
 * must never be rewritten.
 */
class ProseTest {

    @Test fun aPathBecomesALink() =
        assertEquals(
            "open <a href='${GOTO}src/main/resources/db/migration'>src/main/resources/db/migration</a>",
            linkify("open src/main/resources/db/migration")
        )

    @Test fun aBareFilenameWithAKnownExtensionCounts() =
        assertTrue(linkify("edit pom.xml now").contains("href='${GOTO}pom.xml'"))

    @Test fun aShortcutBecomesALink() =
        assertTrue(linkify("press <b>Ctrl+Alt+Insert</b>").contains("href='${INVOKE}Ctrl+Alt+Insert'"))

    @Test fun tagsAreCopiedThroughUntouched() =
        assertEquals("<code>Wallet.java</code>", linkify("<code>Wallet.java</code>").let {
            it.replace(Regex("<a href='[^']*'>"), "").replace("</a>", "")
        })

    @Test fun abbreviationsAreNotFiles() = assertEquals("do it, e.g. now", linkify("do it, e.g. now"))

    @Test fun prosePunctuationIsNotAPath() =
        assertEquals("docker compose ps / docker compose logs", linkify("docker compose ps / docker compose logs"))

    @Test fun aWildcardTriggerNamesNoPath() = assertNull(firstPath("*.sql"))

    /** The migration lesson: the folder to select is only named in the tip. */
    @Test fun theLessonPathFallsBackToTheTip() =
        assertEquals(
            "src/main/resources/db/migration/V1__init.sql",
            lessonPath(
                Lesson(
                    teach = "Select the folder and press <b>Ctrl+Alt+Insert</b>.",
                    trigger = Trigger("fileCreate", "*.sql"),
                    fallbackTip = "touch src/main/resources/db/migration/V1__init.sql",
                )
            )
        )

    @Test fun aFilePathTriggerWins() =
        assertEquals(
            "src/main/resources/application.yml",
            lessonPath(Lesson(trigger = Trigger("fileOpen", "src/main/resources/application.yml")))
        )

    @Test fun shortcutsParseToKeystrokesTheKeymapUses() {
        assertEquals("ctrl alt pressed INSERT", keyStrokeOf("Ctrl+Alt+Insert").toString())
        assertEquals("ctrl pressed N", keyStrokeOf("Ctrl+N").toString())
        assertEquals("shift pressed F10", keyStrokeOf("Shift+F10").toString())
    }

    @Test fun nonShortcutsParseToNothing() {
        assertNull(keyStrokeOf("Shift"))
        assertNull(keyStrokeOf("Tool+Windows"))
    }
}
