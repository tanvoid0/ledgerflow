package io.tanvoid0.codecraft

import org.junit.Assert.assertEquals
import org.junit.Test

class CheatsheetTest {

    @Test
    fun `fills holes and repeats a value`() {
        assertEquals(
            "git switch -c feat/x origin/main; git push -u origin feat/x",
            fill(
                "git switch -c {branch} {base}; git push -u origin {branch}",
                mapOf("branch" to "feat/x", "base" to "origin/main")
            )
        )
    }

    @Test
    fun `an unfilled hole stays visible rather than becoming empty`() {
        assertEquals("git switch {branch}", fill("git switch {branch}", mapOf("branch" to "  ")))
        assertEquals("git switch {branch}", fill("git switch {branch}", emptyMap()))
    }

    @Test
    fun `holes are listed once, in order`() {
        assertEquals(listOf("branch", "base"), holes("git switch -c {branch} {base} # {branch}"))
        assertEquals(emptyList<String>(), holes("git status"))
    }
}
