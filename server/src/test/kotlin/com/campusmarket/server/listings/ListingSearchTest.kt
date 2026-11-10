package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListingSearchTest {

    @Test
    fun browseShowsOnlyActiveListingsOnTheCallersCampus() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val active = env.listing(seller, title = "Active one")
        ListingStatus.entries.filter { it != ListingStatus.ACTIVE }.forEach { env.listing(seller, status = it) }
        val elsewhere = env.user(domain = "other.edu")
        val foreign = env.listing(elsewhere, title = "Active one elsewhere")

        assertEquals(listOf(active), search(viewer).ids())
        assertEquals(listOf(active), search(viewer, "q" to "active").ids())
        assertEquals(listOf(foreign), search(elsewhere).ids())
        // summaries are rendered by the shared reader
        val s = search(viewer).single()
        assertEquals("Active one", s.title)
        assertEquals(seller.id.toString(), s.sellerId)
        assertTrue(s.thumbnailUrl!!.startsWith(env.config.publicBaseUrl))
    }

    @Test
    fun blockedAndSuspendedSellersAreHidden() = apiTest { env ->
        val viewer = env.user()
        val blockedByViewer = env.user()
        val blockedViewer = env.user()
        val suspended = env.user()
        val suspensionOver = env.user()
        val l1 = env.listing(blockedByViewer)
        val l2 = env.listing(blockedViewer)
        val l3 = env.listing(suspended)
        val l4 = env.listing(suspensionOver)
        env.block(viewer, blockedByViewer)
        env.block(blockedViewer, viewer)
        env.db.tx { c ->
            c.update("UPDATE users SET suspended = true WHERE id = ?", suspended.id)
            c.update("UPDATE users SET suspended = true, suspended_until = now() - interval '1 day' WHERE id = ?", suspensionOver.id)
        }

        assertEquals(setOf(l2, l4), search(viewer).ids().toSet())
        assertEquals(setOf(l1, l2, l4), search(env.user()).ids().toSet())
        val textbooks = client.get(ApiRoutes.CATEGORIES) { auth(viewer) }.expect<List<CategoryInfo>>()
            .single { it.category == ListingCategory.TEXTBOOKS }
        assertEquals(2, textbooks.activeCount)
        // the listing itself is still reachable directly
        client.get(ApiRoutes.listing(l1.toString())) { auth(viewer) }.expect<com.campusmarket.core.ListingDetail>()
    }

    @Test
    fun filtersByCategoryConditionAndPrice() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val book = env.listing(seller, category = ListingCategory.TEXTBOOKS, condition = ListingCondition.GOOD, priceCents = 1000)
        val laptop = env.listing(seller, category = ListingCategory.ELECTRONICS, condition = ListingCondition.LIKE_NEW, priceCents = 50000)
        val phone = env.listing(seller, category = ListingCategory.ELECTRONICS, condition = ListingCondition.FAIR, priceCents = 2000)
        val chair = env.listing(seller, category = ListingCategory.FURNITURE, condition = ListingCondition.GOOD, priceCents = 1500)

        assertEquals(setOf(laptop, phone), search(viewer, "category" to "ELECTRONICS").ids().toSet())
        assertEquals(setOf(laptop, phone, chair), search(viewer, "category" to "electronics,FURNITURE").ids().toSet())
        assertEquals(setOf(book, chair), search(viewer, "condition" to "GOOD").ids().toSet())
        assertEquals(setOf(book, chair, laptop), search(viewer, "condition" to "GOOD,LIKE_NEW").ids().toSet())
        val repeated = client.get(ApiRoutes.LISTINGS) {
            auth(viewer)
            parameter("condition", "FAIR")
            parameter("condition", "like_new")
        }.expect<com.campusmarket.core.Page<com.campusmarket.core.ListingSummary>>().items
        assertEquals(setOf(phone, laptop), repeated.ids().toSet())
        assertEquals(setOf(chair, phone), search(viewer, "minPrice" to "1500", "maxPrice" to "2000").ids().toSet())
        assertEquals(setOf(laptop), search(viewer, "minPrice" to "2001").ids().toSet())
        assertEquals(setOf(book), search(viewer, "maxPrice" to "1000", "condition" to "GOOD").ids().toSet())
        assertEquals(listOf(book, chair, phone, laptop), search(viewer, "sort" to "PRICE_ASC").ids())
        assertEquals(listOf(laptop, phone, chair, book), search(viewer, "sort" to "price_desc").ids())

        suspend fun bad(field: String, vararg params: Pair<String, String>) =
            client.get(ApiRoutes.LISTINGS) { auth(viewer); params.forEach { (k, v) -> parameter(k, v) } }
                .expectError(HttpStatusCode.BadRequest, field = field)
        bad("category", "category" to "BOATS")
        bad("condition", "condition" to "GOOD,MINT")
        bad("minPrice", "minPrice" to "-1")
        bad("maxPrice", "maxPrice" to "12.50")
        bad("maxPrice", "minPrice" to "2000", "maxPrice" to "1000")
        bad("sort", "sort" to "CHEAPEST")
        bad("limit", "limit" to "ten")
        bad("cursor", "cursor" to "garbage!")
    }

    @Test
    fun fullTextSearchStemsAndPrefixMatchesTheLastTerm() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val calculus = env.listing(seller, title = "Calculus textbook", description = "Barely used, 8th edition")
        val chemistry = env.listing(seller, title = "Chemistry textbooks bundle", description = "Three books")
        val fridge = env.listing(seller, title = "Mini fridge", description = "Great for dorms, quiet compressor")
        val bike = env.listing(seller, title = "Road bike", description = "Shimano gears, 54cm frame")

        assertEquals(setOf(calculus, chemistry), search(viewer, "q" to "textbooks").ids().toSet()) // stemming
        assertEquals(setOf(calculus, chemistry), search(viewer, "q" to "Textbook").ids().toSet())
        assertEquals(listOf(calculus), search(viewer, "q" to "calc").ids()) // prefix on the last term
        assertEquals(listOf(calculus), search(viewer, "q" to "textbook calcu").ids()) // AND of terms
        assertEquals(emptyList(), search(viewer, "q" to "calc textbook chemistry").ids())
        assertEquals(listOf(fridge), search(viewer, "q" to "dorm fri").ids())
        assertEquals(listOf(bike), search(viewer, "q" to "shim").ids()) // description matches too
        assertEquals(listOf(bike), search(viewer, "q" to "  ROAD   bike!!  ").ids())
        assertEquals(listOf(calculus), search(viewer, "q" to "8th").ids())
        assertEquals(emptyList(), search(viewer, "q" to "unicycle").ids())
        // filters combine with text
        assertEquals(emptyList(), search(viewer, "q" to "textbook", "category" to "BIKES").ids())
        // blank q is plain browsing
        assertEquals(4, search(viewer, "q" to "   ").size)
    }

    @Test
    fun relevanceRanksTitleMatchesAboveDescriptionMatches() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val titleMatch = env.listing(seller, title = "Desk lamp", description = "LED")
        val descMatch = env.listing(seller, title = "Office chair", description = "Matches the desk nicely")
        env.setCreatedAt(titleMatch, Instant.parse("2026-01-01T00:00:00Z")) // older, but more relevant
        env.listing(seller, title = "Bookshelf", description = "Oak")

        assertEquals(listOf(titleMatch, descMatch), search(viewer, "q" to "desk").ids()) // RELEVANCE is the default with q
        assertEquals(listOf(descMatch, titleMatch), search(viewer, "q" to "desk", "sort" to "NEWEST").ids())
        // RELEVANCE without q falls back to NEWEST
        assertEquals(3, search(viewer, "sort" to "RELEVANCE").size)
    }

    @Test
    fun relevanceIsEveryTermInTheTitleThenSomeThenDescriptionOnlyEachNewestFirst() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val t = Instant.parse("2026-10-01T12:00:00Z")
        fun listing(title: String, description: String, age: Long) =
            env.listing(seller, title = title, description = description, withPhoto = false).also { env.setCreatedAt(it, t.minusSeconds(age)) }
        val bodyNew = listing("Table", "An ikea desk would be nicer", 1)
        val someNew = listing("Desk", "From ikea, keeps mentioning ikea desk ikea desk", 2)
        val allNew = listing("IKEA desk", "White", 3)
        val bodyOld = listing("Chair", "Goes with my ikea desks", 4)
        val someOld = listing("IKEA shelf", "Sits on a desk", 5)
        val allOld = listing("Standing desk by IKEA", "Oak, ikea desk", 6)
        listing("IKEA lamp", "Bright", 7) // one term only: no match

        assertEquals(listOf(allNew, allOld, someNew, someOld, bodyNew, bodyOld), search(viewer, "q" to "ikea desk").ids())
        assertEquals(listOf(allNew, allOld, someNew, someOld, bodyNew, bodyOld), allPages(viewer, ApiRoutes.LISTINGS, 1, mapOf("q" to "ikea desk")).ids())
        assertEquals(listOf(someNew, allNew, allOld, bodyNew, bodyOld, someOld), search(viewer, "q" to "desk").ids())
        // short queries: title starts with it, title contains it, description only
        val startsOld = listing("TV stand", "Black", 8)
        val inTitle = listing("Smart TV", "55 inch", 9)
        val inBody = listing("Mount", "Fits any tv", 10)
        val startsNew = listing("Tv antenna", "Indoor", 0)
        assertEquals(listOf(startsNew, startsOld, inTitle, inBody), search(viewer, "q" to "tv").ids())
        assertEquals(listOf(startsNew, startsOld, inTitle, inBody), allPages(viewer, ApiRoutes.LISTINGS, 1, mapOf("q" to "tv")).ids())
    }

    @Test
    fun shortAndStopWordQueriesFallBackToSubstringMatching() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val stand = env.listing(seller, title = "TV stand", description = "Holds a 55 inch")
        val lg = env.listing(seller, title = "LG television", description = "Comes with remote, works with any tv mount")
        val hobbit = env.listing(seller, title = "The Hobbit", description = "Paperback")
        env.listing(seller, title = "Desk", description = "Oak")

        assertEquals(listOf(stand, lg), search(viewer, "q" to "tv").ids()) // title prefix ranks first
        assertEquals(listOf(stand, lg), search(viewer, "q" to "TV").ids())
        assertTrue(hobbit in search(viewer, "q" to "the").ids()) // stop words only
        assertEquals(listOf(hobbit), search(viewer, "q" to "The Hobbit").ids())
    }

    @Test
    fun hostileQueriesAreHandledSafely() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        env.listing(seller, title = "Calculus textbook")
        env.listing(seller, title = "100% cotton shirt", description = "size_m")
        val hostile = listOf(
            "foo & bar", "foo | !bar", "(", ")", "':*", "\\", "a:* & b", "!!!", "<->", "foo <-> bar", "'", "''", "\"quoted\"",
            "'; DROP TABLE listings; --", "calc'*", "calc:*", "calc\u0000", "a\u0000", "\u0000", "🙂", "😀 textbook", "x".repeat(5000), "t".repeat(3000) + " calc",
            "‮calc", "%", "_", "%%", "!", "a", "the of and", "ÜBER größe", "教科書",
        )
        for (q in hostile) {
            val res = client.get(ApiRoutes.LISTINGS) { auth(viewer); parameter("q", q) }
            res.expect<com.campusmarket.core.Page<com.campusmarket.core.ListingSummary>>()
        }
        // LIKE wildcards in short queries are literal
        assertEquals(listOf("100% cotton shirt"), search(viewer, "q" to "%").map { it.title })
        assertEquals(listOf("100% cotton shirt"), search(viewer, "q" to "_").map { it.title })
        assertEquals(emptyList(), search(viewer, "q" to "%%").map { it.title })
        // operators are ignored rather than interpreted: this is "calculus AND textbook*", not "NOT textbook"
        assertEquals(listOf("Calculus textbook"), search(viewer, "q" to "calculus:* & !textbook").map { it.title })
        assertEquals(2, env.db.tx { c -> c.queryOne("SELECT count(*) FROM listings") { it.getInt(1) } })
    }

    @Test
    fun categoriesListEveryCategoryWithCampusCounts() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        repeat(2) { env.listing(seller, category = ListingCategory.BIKES) }
        env.listing(seller, category = ListingCategory.TEXTBOOKS)
        env.listing(seller, category = ListingCategory.TEXTBOOKS, status = ListingStatus.SOLD)
        env.listing(env.user(domain = "other.edu"), category = ListingCategory.TEXTBOOKS)

        val cats = client.get(ApiRoutes.CATEGORIES) { auth(viewer) }.expect<List<CategoryInfo>>()
        assertEquals(ListingCategory.entries, cats.map { it.category })
        assertEquals(ListingCategory.entries.map { it.label }, cats.map { it.label })
        val counts = cats.associate { it.category to it.activeCount }
        assertEquals(2, counts[ListingCategory.BIKES])
        assertEquals(1, counts[ListingCategory.TEXTBOOKS])
        assertEquals(0, counts[ListingCategory.FURNITURE])
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.CATEGORIES).status)
    }
}
