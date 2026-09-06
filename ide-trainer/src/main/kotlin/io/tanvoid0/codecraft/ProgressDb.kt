package io.tanvoid0.codecraft

import java.nio.file.Path
import java.sql.Connection

/**
 * Where progress actually lives: one SQLite file per experiment, next to its
 * step files.
 *
 * The step files keep the *curriculum* — authored prose that belongs in files
 * you can diff and edit — and `progress.json` keeps a diffable copy of the
 * ticks. But neither has any history: they know a task is done, never when,
 * and a file rewound by a checkout takes the ticks with it. This holds the
 * tick, the moment it happened, and every event before it — which is what
 * "started 20m ago", "6 today" and reopening on the right step are read from.
 *
 * One connection per file, owned by the [CurriculumStore] for that experiment.
 * Everything else that needs lesson state goes through the store rather than
 * opening its own.
 */
class ProgressDb private constructor(db: Connection) : SqliteDb(db) {

    /** The outcome of a setup check, the moment it ran, and why it failed if it did. */
    data class Check(val ok: Boolean, val at: Long, val reason: String? = null)

    companion object {
        fun open(path: Path): ProgressDb? = openSqlite(path, ::ProgressDb)
    }

    override fun migrate() {
        update("create table if not exists tick(key text primary key, at integer not null)")
        update("create table if not exists event(at integer not null, key text not null, done integer not null)")
        update("create table if not exists lesson(id text primary key, state text not null, at integer not null)")
        update("create table if not exists check_run(name text primary key, ok integer not null, at integer not null)")
        update("create index if not exists event_at on event(at)")
        // Added after check_run shipped without it - a fresh table already has
        // the column, so failure here just means an existing one caught up.
        runCatching { update("alter table check_run add column reason text") }
    }

    // ---- ticks -----------------------------------------------------------

    fun ticks(): MutableSet<String> = query("select key from tick") { it.getString(1) }.toHashSet()

    fun setTick(key: String, done: Boolean, at: Long) {
        if (done) update("insert or replace into tick(key, at) values(?, ?)", key, at)
        else update("delete from tick where key = ?", key)
        update("insert into event(at, key, done) values(?, ?, ?)", at, key, if (done) 1 else 0)
    }

    /**
     * The whole tick set at once, for adopting what another writer left. The
     * difference is recorded as events, so the history stays honest about what
     * was added and what was taken away.
     */
    fun replaceTicks(keys: Set<String>, at: Long) {
        val had = ticks()
        transaction {
            update("delete from tick")
            keys.forEach { update("insert into tick(key, at) values(?, ?)", it, at) }
            (keys - had).forEach { update("insert into event(at, key, done) values(?, ?, 1)", at, it) }
            (had - keys).forEach { update("insert into event(at, key, done) values(?, ?, 0)", at, it) }
        }
    }

    /** When this database was last written, or null if nothing has happened in it. */
    fun lastWriteAt(): Long? = longOrNull("select max(at) from event")

    /** First tick in a step — what "started 20m ago" counts from. */
    fun startedStepAt(stepId: String): Long? = longOrNull("select min(at) from event where key like ?", "$stepId:%")

    /** The step the learner last touched, complete or not. */
    fun lastTouchedStep(): String? =
        query("select key from event order by at desc limit 1") { it.getString(1) }
            .firstOrNull()?.substringBefore(':')

    fun ticksSince(at: Long): Int = longOrNull("select count(*) from event where done = 1 and at >= ?", at)?.toInt() ?: 0

    /** Every moment a task was ticked on, for turning into calendar days by the caller's own zone. */
    fun doneTimestamps(): List<Long> = query("select at from event where done = 1") { it.getLong(1) }

    // ---- lessons ---------------------------------------------------------

    fun lessons(): Map<String, LessonState> =
        query("select id, state from lesson") { it.getString(1) to it.getString(2) }
            .mapNotNull { (id, name) -> runCatching { id to LessonState.valueOf(name.uppercase()) }.getOrNull() }
            .toMap()

    fun setLesson(id: String, state: LessonState, at: Long) {
        update("insert or replace into lesson(id, state, at) values(?, ?, ?)", id, state.name.lowercase(), at)
    }

    // ---- setup checks ----------------------------------------------------
    //
    // Their own table rather than a tick key: the tick set is replaced wholesale
    // whenever the JSON wins a merge, and a check recorded as a tick would go
    // with it.

    /** What each setup check last said, and when it said it. */
    fun checks(): Map<String, Check> =
        query("select name, ok, at, reason from check_run") {
            it.getString(1) to Check(it.getInt(2) == 1, it.getLong(3), it.getString(4))
        }.toMap()

    fun setCheck(name: String, ok: Boolean, at: Long, reason: String? = null) {
        update(
            "insert or replace into check_run(name, ok, at, reason) values(?, ?, ?, ?)",
            name, if (ok) 1 else 0, at, reason,
        )
    }
}
