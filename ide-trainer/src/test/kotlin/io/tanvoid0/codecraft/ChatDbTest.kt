package io.tanvoid0.codecraft

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The chat panel's own database — one per project, holding conversations and
 * the agent's task list and memory. Separate from an experiment's progress
 * database: a conversation is not about a curriculum.
 */
class ChatDbTest : TestCase() {

    private lateinit var dir: Path
    private lateinit var db: ChatDb

    private val t0 = 1_700_000_000_000L

    override fun setUp() {
        dir = Files.createTempDirectory("chat-db")
        db = ChatDb.open(dir.resolve("chats.db")) ?: error("could not open db")
    }

    override fun tearDown() {
        db.close()
    }


    private fun turn(role: String, text: String) = ChatDb.Turn(role, text)

    fun testChatRoundTrips() {
        val id = db.saveChat(null, "why does this fail", t0, 7, listOf(turn("user", "why"), turn("assistant", "because")))

        val chat = db.chats().single()
        assertEquals("why does this fail", chat.title)
        assertEquals(7, chat.threadId)
        assertEquals(listOf(turn("user", "why"), turn("assistant", "because")), db.turns(id))
    }

    /** Saving after every turn must update the one conversation, not pile up copies of it. */
    fun testSavingAgainUpdatesInPlace() {
        val id = db.saveChat(null, "first", t0, null, listOf(turn("user", "one")))
        val same = db.saveChat(id, "first", t0 + 1, null, listOf(turn("user", "one"), turn("assistant", "two")))

        assertEquals(id, same)
        assertEquals(1, db.chats().size)
        assertEquals(2, db.turns(id).size)
    }

    /** The history list is "most recent first", so ordering is the feature. */
    fun testChatsComeBackNewestFirst() {
        db.saveChat(null, "older", t0, null, listOf(turn("user", "a")))
        db.saveChat(null, "newer", t0 + 1000, null, listOf(turn("user", "b")))

        assertEquals(listOf("newer", "older"), db.chats().map { it.title })
    }

    /** A thread id with no value must come back null, not 0 - 0 is a real thread. */
    fun testNullThreadIdStaysNull() {
        db.saveChat(null, "no thread", t0, null, listOf(turn("user", "a")))

        assertNull(db.chats().single().threadId)
    }

    fun testDeletingAChatTakesItsTurnsWithIt() {
        val id = db.saveChat(null, "doomed", t0, null, listOf(turn("user", "a")))

        db.deleteChat(id)

        assertTrue(db.chats().isEmpty())
        assertTrue(db.turns(id).isEmpty())
    }

    // ---- agent task list and memory --------------------------------------

    /** Open work first: the list is the order the agent should work in. */
    fun testOpenTasksSortAheadOfDoneOnes() {
        val first = db.addTask("one", t0)
        db.addTask("two", t0 + 1)
        db.completeTask(first)

        assertEquals(listOf("two", "one"), db.tasks().map { it.text })
        assertEquals(listOf(false, true), db.tasks().map { it.done })
    }

    /** Completing a task nobody has must report failure, not pretend it worked. */
    fun testCompletingAMissingTaskReportsFailure() {
        assertFalse(db.completeTask(404L))
    }

    fun testClearingDoneKeepsOpenTasks() {
        val done = db.addTask("done", t0)
        db.addTask("open", t0 + 1)
        db.completeTask(done)

        db.clearDoneTasks()

        assertEquals(listOf("open"), db.tasks().map { it.text })
    }

    /** Re-learning a fact must overwrite it, or memory fills with near-duplicates. */
    fun testRememberingTheSameKeyOverwrites() {
        db.remember("build", "maven", t0)
        db.remember("build", "gradle", t0 + 1)

        assertEquals(mapOf("build" to "gradle"), db.memories())
    }

    fun testForgettingRemovesOnlyThatKey() {
        db.remember("a", "1", t0)
        db.remember("b", "2", t0 + 1)

        db.forget("a")

        assertEquals(mapOf("b" to "2"), db.memories())
    }
}
