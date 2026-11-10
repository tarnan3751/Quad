package com.campusmarket.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.PGStatement
import org.slf4j.LoggerFactory
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Thin JDBC wrapper. All SQL is hand-written; see the helper extensions below. */
class Db(val dataSource: DataSource) : AutoCloseable {

    /** Runs [block] in a transaction on the IO dispatcher. Commits on success, rolls back on any exception. */
    suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) { txBlocking(block) }

    fun <T> txBlocking(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val result = block(c)
            c.commit()
            result
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw e
        }
    }

    /**
     * Runs a single read statement in autocommit mode: no transaction, so no COMMIT round trip. Only for one statement
     * (several would each see a different snapshot).
     */
    fun <T> readBlocking(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = true
        block(c)
    }

    override fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(Db::class.java)

        fun connect(url: String, user: String, password: String, poolSize: Int = 10): Db {
            val cfg = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = poolSize
                isAutoCommit = false
                // a request waits at most 5 s for a free connection (Hikari's default is 30 s; clients give up sooner)
                connectionTimeout = 5_000
            }
            return Db(HikariDataSource(cfg))
        }

        /**
         * Applies every `db/migration/V*.sql` classpath resource that hasn't been applied yet, in
         * natural (numeric-aware) filename order. Each file runs in its own transaction.
         */
        fun migrate(db: Db) {
            db.txBlocking { c ->
                c.createStatement().use {
                    it.execute("CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
                }
            }
            val applied = db.txBlocking { c -> c.query("SELECT name FROM schema_migrations") { it.getString(1) }.toSet() }
            for (name in listMigrations().sortedWith(MigrationOrder)) {
                if (name in applied) continue
                val sql = Db::class.java.classLoader.getResource("db/migration/$name")!!.readText()
                db.txBlocking { c ->
                    c.createStatement().use { it.execute(sql) }
                    c.update("INSERT INTO schema_migrations (name) VALUES (?)", name)
                }
                log.info("Applied migration {}", name)
            }
        }

        private fun listMigrations(): List<String> {
            val urls = Db::class.java.classLoader.getResources("db/migration").toList()
            val names = mutableSetOf<String>()
            for (url in urls) {
                when (url.protocol) {
                    "file" -> Files.list(Paths.get(url.toURI())).use { s -> s.forEach { names += it.fileName.toString() } }
                    "jar" -> (url.openConnection() as JarURLConnection).jarFile.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith("db/migration/") && !it.endsWith("/") }
                        .forEach { names += it.substringAfterLast('/') }
                }
            }
            return names.filter { it.startsWith("V") && it.endsWith(".sql") }
        }

        /** Orders V1__a.sql < V2_chat_1__b.sql < V2_chat_2__c.sql < V10__d.sql (numeric chunks compare as numbers). */
        private object MigrationOrder : Comparator<String> {
            private val chunk = Regex("\\d+|\\D+")
            override fun compare(a: String, b: String): Int {
                val ca = chunk.findAll(a).map { it.value }.toList()
                val cb = chunk.findAll(b).map { it.value }.toList()
                for (i in 0 until minOf(ca.size, cb.size)) {
                    val x = ca[i]; val y = cb[i]
                    val r = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
                    if (r != 0) return r
                }
                return ca.size - cb.size
            }
        }
    }
}

// ---------- JDBC helpers ----------

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { i, p ->
        val idx = i + 1
        when (p) {
            null -> setNull(idx, Types.NULL)
            is UUID -> setObject(idx, p)
            is String -> setString(idx, p)
            is Int -> setInt(idx, p)
            is Long -> setLong(idx, p)
            is Boolean -> setBoolean(idx, p)
            is Double -> setDouble(idx, p)
            is Instant -> setTimestamp(idx, Timestamp.from(p))
            is Enum<*> -> setString(idx, p.name)
            is Collection<*> -> {
                // bind as a SQL array; use with `= ANY(?)`. Element type inferred from the first element.
                val first = p.firstOrNull()
                val type = when (first) {
                    is UUID -> "uuid"
                    is Int -> "int4"
                    is Long -> "int8"
                    else -> "text"
                }
                val elems = if (first is Enum<*>) p.map { (it as Enum<*>).name }.toTypedArray() else p.toTypedArray()
                setArray(idx, connection.createArrayOf(type, elems))
            }
            else -> setObject(idx, p)
        }
    }
}

/** Runs a SELECT and maps every row. Parameters: UUID, String, Int, Long, Boolean, Double, Instant, Enum (by name), Collection (as array, for `= ANY(?)`), null. */
fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> = select(sql, params, freshPlan = false, map)

/**
 * [query] for a statement whose best plan depends on its parameter values (search terms, filters): Postgres plans it
 * for the actual values on every call instead of settling on a generic cached plan after a few executions.
 */
fun <T> Connection.queryFreshPlan(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> = select(sql, params, freshPlan = true, map)

private fun <T> Connection.select(sql: String, params: Array<out Any?>, freshPlan: Boolean, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { ps ->
        // never a named server-side statement: the unnamed one is planned with the bound values
        if (freshPlan) ps.unwrap(PGStatement::class.java).prepareThreshold = 0
        ps.bind(params)
        ps.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out += map(rs)
            out
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

/** Runs INSERT/UPDATE/DELETE and returns the affected row count. */
fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { ps ->
        ps.bind(params)
        ps.executeUpdate()
    }

fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col, UUID::class.java)
fun ResultSet.instant(col: String): Instant = getTimestamp(col).toInstant()
fun ResultSet.instantOrNull(col: String): Instant? = getTimestamp(col)?.toInstant()
fun ResultSet.millis(col: String): Long = getTimestamp(col).time
fun ResultSet.millisOrNull(col: String): Long? = getTimestamp(col)?.time
fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }

fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
