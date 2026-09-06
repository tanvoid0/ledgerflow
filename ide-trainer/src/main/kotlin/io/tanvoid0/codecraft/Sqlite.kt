package io.tanvoid0.codecraft

import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

/**
 * The JDBC plumbing the plugin's two databases share.
 *
 * The IDE bundles its own SQLite, but every class in it is
 * `@ApiStatus.Internal` and `verifyPlugin` fails the build over that, so this
 * uses the ordinary driver. The connection is made straight from
 * `SQLiteDataSource` rather than through `DriverManager`: a plugin lives in
 * its own classloader, which the driver's service lookup does not reach.
 *
 * Timestamps everywhere are epoch millis, not ISO strings: `Instant.toString()`
 * prints a variable number of fraction digits, so `order by at` over text would
 * sort `12:00:00Z` after `12:00:00.5Z`.
 */
abstract class SqliteDb internal constructor(private val db: Connection) : AutoCloseable {

    /** Creates this database's own tables, and only its own. */
    internal abstract fun migrate()

    override fun close() {
        runCatching { db.close() }
    }

    protected fun update(sql: String, vararg args: Any?) = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeUpdate()
    }

    protected fun <T> query(sql: String, vararg args: Any, row: (ResultSet) -> T): List<T> =
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                ArrayList<T>().also { while (rs.next()) it.add(row(rs)) }
            }
        }

    /** `max()` over no rows is one row holding null, which reads back as 0. */
    protected fun longOrNull(sql: String, vararg args: Any): Long? =
        query(sql, *args) { if (it.getObject(1) == null) null else it.getLong(1) }.firstOrNull()

    protected fun transaction(body: () -> Unit) {
        db.autoCommit = false
        try {
            body()
            db.commit()
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }
}

/** Null if the file cannot be opened — every caller then runs without a database. */
internal fun <T : SqliteDb> openSqlite(path: Path, make: (Connection) -> T): T? = runCatching {
    path.parent?.let { Files.createDirectories(it) }
    val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:${path.toAbsolutePath()}" }
    make(ds.connection).apply { migrate() }
}.getOrNull()
