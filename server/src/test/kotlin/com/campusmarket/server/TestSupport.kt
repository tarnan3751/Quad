package com.campusmarket.server

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID

/**
 * Integration-test harness. Each test JVM gets its own throwaway database on the docker-compose
 * Postgres (127.0.0.1:5434, start it with `docker compose up -d`), migrated once; tables are truncated
 * between tests via [TestEnv.reset].
 *
 * Usage:
 * ```
 * @Test fun example() = apiTest { env ->
 *     val alice = env.user("alice")
 *     val res = client.get(ApiRoutes.ME) { auth(alice) }
 * }
 * ```
 */
object TestEnv {
    private const val ADMIN_URL = "jdbc:postgresql://127.0.0.1:5434/postgres"
    private const val USER = "campus"
    private const val PASSWORD = "campus"
    private val dbName = "cm_test_" + UUID.randomUUID().toString().replace("-", "").take(12)

    val config: AppConfig = AppConfig(
        dbUrl = "jdbc:postgresql://127.0.0.1:5434/$dbName",
        dbUser = USER,
        dbPassword = PASSWORD,
        publicBaseUrl = "http://localhost",
        devMode = true,
        uploadsDir = Files.createTempDirectory("cm-uploads").toString(),
    )

    val db: Db by lazy {
        DriverManager.getConnection(ADMIN_URL, USER, PASSWORD).use { it.createStatement().execute("CREATE DATABASE $dbName") }
        val db = Db.connect(config.dbUrl, USER, PASSWORD, poolSize = 4)
        Db.migrate(db)
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { db.close() }
            runCatching {
                DriverManager.getConnection(ADMIN_URL, USER, PASSWORD).use { it.createStatement().execute("DROP DATABASE IF EXISTS $dbName WITH (FORCE)") }
            }
        })
        db
    }

    /** Deps with the default test config. Use `AppDeps(config.copy(...), db)` for config variations. */
    val deps: AppDeps by lazy { AppDeps(config, db) }

    /** Empties every table (except schema_migrations). */
    fun reset() {
        db.txBlocking { c ->
            val tables = c.query(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'schema_migrations'",
            ) { it.getString(1) }
            if (tables.isNotEmpty()) c.createStatement().use { it.execute("TRUNCATE ${tables.joinToString()} CASCADE") }
        }
    }

    // ---------- fixtures (direct inserts; independent of feature endpoints) ----------

    data class TestUser(val id: UUID, val email: String, val token: String)

    /** Inserts a verified user and mints an access token for them. */
    fun user(
        name: String = "user" + UUID.randomUUID().toString().take(6),
        domain: String = "state.edu",
        role: UserRole = UserRole.USER,
        payoutsEnabled: Boolean = false,
    ): TestUser {
        val id = UUID.randomUUID()
        val email = "${name.lowercase()}@$domain"
        db.txBlocking { c ->
            c.update(
                """INSERT INTO users (id, email, password_hash, display_name, campus_domain, role, email_verified,
                                      stripe_account_id, stripe_details_submitted, payouts_enabled)
                   VALUES (?, ?, 'x', ?, ?, ?, true, ?, ?, ?)""",
                id, email, name, domain, role, if (payoutsEnabled) "acct_test_$name" else null, payoutsEnabled, payoutsEnabled,
            )
        }
        return TestUser(id, email, deps.jwt.issueAccessToken(id).token)
    }

    fun photo(owner: TestUser): UUID {
        val id = UUID.randomUUID()
        db.txBlocking { c ->
            c.update(
                "INSERT INTO photos (id, owner_id, storage_key, content_type, size_bytes) VALUES (?, ?, ?, 'image/jpeg', 1234)",
                id, owner.id, "$id.jpg",
            )
        }
        return id
    }

    /** Inserts a listing (with one photo unless [withPhoto] is false) on the seller's campus. */
    fun listing(
        seller: TestUser,
        title: String = "Calculus textbook",
        description: String = "Barely used",
        priceCents: Int = 2500,
        category: ListingCategory = ListingCategory.TEXTBOOKS,
        condition: ListingCondition = ListingCondition.GOOD,
        status: ListingStatus = ListingStatus.ACTIVE,
        withPhoto: Boolean = true,
    ): UUID {
        val id = UUID.randomUUID()
        val domain = seller.email.substringAfter('@')
        db.txBlocking { c ->
            c.update(
                """INSERT INTO listings (id, seller_id, campus_domain, title, description, category, condition, price_cents, status)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, seller.id, domain, title, description, category, condition, priceCents, status,
            )
        }
        if (withPhoto) {
            val photoId = photo(seller)
            db.txBlocking { c -> c.update("INSERT INTO listing_photos (listing_id, photo_id, position) VALUES (?, ?, 0)", id, photoId) }
        }
        return id
    }
}

/** Runs [block] against the full application with a clean database and a JSON-capable client. */
fun apiTest(deps: AppDeps = TestEnv.deps, block: suspend ApiTestScope.(TestEnv) -> Unit) = testApplication {
    TestEnv.db // force database creation + migration
    TestEnv.reset()
    application { module(deps) }
    val client = createClient {
        install(ContentNegotiation) { json(ApiJson) }
        install(WebSockets)
    }
    ApiTestScope(this, client).block(TestEnv)
}

class ApiTestScope(val app: ApplicationTestBuilder, val client: HttpClient)

fun HttpRequestBuilder.auth(user: TestEnv.TestUser) = bearerAuth(user.token)

/** Sets a JSON body (serialized with the contract's Json config). */
inline fun <reified T> HttpRequestBuilder.json(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
