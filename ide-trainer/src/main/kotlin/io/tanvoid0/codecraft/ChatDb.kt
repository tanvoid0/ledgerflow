package io.tanvoid0.codecraft

import java.nio.file.Path
import java.sql.Connection

/**
 * The chat panel's own database: one per *project*, not per experiment. A
 * conversation is not about a curriculum, and storing it in an experiment's
 * `trainer.db` would hide it the moment you switched paths.
 *
 * The agent's task list and memory live here for the same reason — they belong
 * to the project, not to whichever path happens to be selected.
 */
class ChatDb private constructor(db: Connection) : SqliteDb(db) {

    companion object {
        fun open(path: Path): ChatDb? = openSqlite(path, ::ChatDb)
    }

    override fun migrate() {
        update("create table if not exists chat(id integer primary key autoincrement, title text not null, at integer not null, thread_id integer)")
        update("create table if not exists chat_turn(chat integer not null, seq integer not null, role text not null, text text not null, primary key(chat, seq))")
        update("create table if not exists task(id integer primary key autoincrement, text text not null, done integer not null default 0, at integer not null)")
        update("create table if not exists memory(key text primary key, value text not null, at integer not null)")
    }

    // ---- chat sessions ---------------------------------------------------

    /** One saved conversation, without its turns - what the history list shows. */
    data class Chat(val id: Long, val title: String, val at: Long, val threadId: Int?)

    /** A single message in a saved conversation. `role` is "user" or "assistant". */
    data class Turn(val role: String, val text: String)

    /** Newest first: the history list wants the last conversation at the top. */
    fun chats(limit: Int = 50): List<Chat> =
        query("select id, title, at, thread_id from chat order by at desc limit ?", limit) {
            Chat(it.getLong(1), it.getString(2), it.getLong(3), it.getObject(4)?.let { _ -> it.getInt(4) })
        }

    fun turns(chatId: Long): List<Turn> =
        query("select role, text from chat_turn where chat = ? order by seq", chatId) {
            Turn(it.getString(1), it.getString(2))
        }

    /**
     * Writes a whole conversation at once, replacing the turns it had. Chat is
     * append-mostly but a turn is only interesting alongside the ones around
     * it, so rewriting the set is simpler than diffing it and costs nothing at
     * these sizes. Returns the id, which a new conversation only learns here.
     */
    fun saveChat(id: Long?, title: String, at: Long, threadId: Int?, turns: List<Turn>): Long {
        var chatId = id
        transaction {
            if (chatId == null) {
                update("insert into chat(title, at, thread_id) values(?, ?, ?)", title, at, threadId)
                chatId = longOrNull("select last_insert_rowid()")
            } else {
                update("update chat set title = ?, at = ?, thread_id = ? where id = ?", title, at, threadId, chatId)
            }
            update("delete from chat_turn where chat = ?", chatId)
            turns.forEachIndexed { i, t ->
                update("insert into chat_turn(chat, seq, role, text) values(?, ?, ?, ?)", chatId, i, t.role, t.text)
            }
        }
        return chatId ?: error("no id for saved chat")
    }

    fun deleteChat(id: Long) = transaction {
        update("delete from chat_turn where chat = ?", id)
        update("delete from chat where id = ?", id)
    }

    // ---- the agent's task list and memory --------------------------------

    data class Task(val id: Long, val text: String, val done: Boolean)

    /** Open tasks first, oldest first within each group - the order to work in. */
    fun tasks(): List<Task> =
        query("select id, text, done from task order by done, at") { Task(it.getLong(1), it.getString(2), it.getInt(3) == 1) }

    fun addTask(text: String, at: Long): Long {
        update("insert into task(text, done, at) values(?, 0, ?)", text, at)
        return longOrNull("select last_insert_rowid()") ?: error("no id for task")
    }

    /** Returns false if no such task, so the agent is told rather than silently ignored. */
    fun completeTask(id: Long): Boolean {
        update("update task set done = 1 where id = ?", id)
        return longOrNull("select count(*) from task where id = ? and done = 1", id) == 1L
    }

    fun clearDoneTasks() = update("delete from task where done = 1")

    /**
     * Memory is a key/value note the agent keeps across conversations - "this
     * project uses Gradle, not Maven". Keyed so re-learning the same fact
     * overwrites rather than accumulating near-duplicates.
     */
    fun memories(): Map<String, String> =
        query("select key, value from memory order by at") { it.getString(1) to it.getString(2) }.toMap()

    fun remember(key: String, value: String, at: Long) =
        update("insert or replace into memory(key, value, at) values(?, ?, ?)", key, value, at)

    fun forget(key: String) = update("delete from memory where key = ?", key)
}
