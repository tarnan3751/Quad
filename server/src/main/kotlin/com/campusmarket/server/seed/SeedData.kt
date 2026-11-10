package com.campusmarket.server.seed

import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCategory.*
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingCondition.*
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingStatus.*
import com.campusmarket.core.UserRole

/** Static demo dataset. Keys are stable: ids are derived from them so re-running the seed is a no-op. */
object SeedData {
    const val PASSWORD = "password123"

    data class U(
        val key: String,
        val name: String,
        val domain: String,
        val role: UserRole = UserRole.USER,
        val seller: Boolean = false,
        val bio: String = "",
    ) {
        val email get() = "$key@$domain"
    }

    val users = listOf(
        U("admin", "Alex Admin", "state.edu", UserRole.ADMIN, seller = true, bio = "Quad admin account."),
        U("moderator", "Morgan Moderator", "state.edu", UserRole.MODERATOR, bio = "Keeping the marketplace friendly."),
        U("maya", "Maya Chen", "state.edu", seller = true, bio = "Junior, biology. Clearing out my dorm before study abroad."),
        U("jordan", "Jordan Reyes", "state.edu", seller = true, bio = "CS senior. Selling gear I no longer need."),
        U("priya", "Priya Patel", "state.edu", seller = true, bio = "Grad student, happy to meet at the library."),
        U("liam", "Liam O'Connor", "state.edu", bio = "Looking for cheap furniture and bikes."),
        U("sofia", "Sofia Alvarez", "state.edu", seller = true, bio = "Thrift queen. Everything is clean and from a smoke-free home."),
        U("noah", "Noah Williams", "state.edu", bio = "Freshman. Buying textbooks."),
        U("dev", "Dev Sharma", "tech.edu", seller = true, bio = "EE major. Electronics and parts."),
        U("ava", "Ava Johnson", "tech.edu", seller = true, bio = "Climbing and cycling stuff."),
        U("ethan", "Ethan Brooks", "tech.edu", bio = "Always hunting for deals."),
        U("zoe", "Zoe Kim", "tech.edu", bio = "Sophomore, architecture."),
    )

    /** [buyer] is the buyer of the order for SOLD (completed) and RESERVED (awaiting handoff / payment) listings. */
    data class L(
        val seller: String,
        val title: String,
        val desc: String,
        val cat: ListingCategory,
        val cond: ListingCondition,
        val cents: Int,
        val status: ListingStatus = ACTIVE,
        val buyer: String? = null,
        val photos: Int = 2,
        val pickup: String = "",
    ) {
        val key get() = key(seller, title)
    }

    fun key(seller: String, title: String) = "$seller:$title"

    val listings = listOf(
        // state.edu
        L("maya", "Campbell Biology, 12th edition", "Hardcover, a few highlighted chapters but no torn pages. Required for BIO 201.", TEXTBOOKS, GOOD, 6500, pickup = "Science Library lobby"),
        L("maya", "Mini fridge, 3.2 cu ft", "Works perfectly, has a small freezer compartment. Selling because I'm moving out.", DORM, GOOD, 6000, SOLD, "liam", 3, "Hartwell Hall front desk"),
        L("maya", "Desk lamp with USB port", "Adjustable LED lamp, three brightness levels.", DORM, LIKE_NEW, 1200, pickup = "Hartwell Hall"),
        L("maya", "Floral summer dress, size S", "Worn twice. Light cotton, perfect for spring.", CLOTHING, LIKE_NEW, 1800, photos = 1),
        L("maya", "Yoga mat and blocks", "Thick 6mm mat with two foam blocks and a strap.", SPORTS, GOOD, 1500, pickup = "Rec center entrance"),
        L("jordan", "TI-84 Plus CE graphing calculator", "Works great, includes charging cable and slide case.", ELECTRONICS, GOOD, 5500, SOLD, "noah", 2, "Engineering building"),
        L("jordan", "Mechanical keyboard, brown switches", "Tenkeyless, USB-C, white backlight. Minor wear on the spacebar.", ELECTRONICS, GOOD, 4500, pickup = "CS building lobby"),
        L("jordan", "27-inch 1440p monitor", "IPS panel, HDMI and DisplayPort cables included. No dead pixels.", ELECTRONICS, LIKE_NEW, 11000, RESERVED, "sofia", 2, "CS building lobby"),
        L("jordan", "Introduction to Algorithms (CLRS), 4th ed", "Excellent condition, never highlighted.", TEXTBOOKS, LIKE_NEW, 7500, photos = 1),
        L("jordan", "Noise-cancelling headphones", "Over-ear bluetooth, about 30 hours of battery. Case included.", ELECTRONICS, GOOD, 8000),
        L("jordan", "Road bike, 54cm aluminium frame", "Shimano 105 groupset, recently tuned. Helmet not included.", BIKES, GOOD, 28000, photos = 3, pickup = "Bike racks by the student union"),
        L("priya", "Organic Chemistry, Clayden 2nd ed", "Some pencil notes in the margins. Great reference.", TEXTBOOKS, FAIR, 4000),
        L("priya", "Lab coat, size M", "Washed, no stains. Required for CHEM labs.", CLOTHING, GOOD, 1500, photos = 1),
        L("priya", "Electric kettle, 1.7L", "Stainless steel, auto shut-off. Used for one semester.", DORM, LIKE_NEW, 1800),
        L("priya", "Scientific calculator Casio fx-991", "Exam approved. Works perfectly.", ELECTRONICS, GOOD, 1500, photos = 1),
        L("priya", "Bookshelf, 5 shelves", "Sturdy particle board, white. Easy to disassemble.", FURNITURE, FAIR, 2500, pickup = "Greenfield Apartments"),
        L("liam", "Full-size futon couch", "Black metal frame, fits two people. Mattress is clean.", FURNITURE, GOOD, 9000),
        L("liam", "Football tickets vs Tech, section 112", "Two tickets, can transfer digitally.", TICKETS, NEW, 6000, photos = 1),
        L("sofia", "Vintage denim jacket, size M", "Perfectly worn-in. Thrifted but never worn by me.", CLOTHING, GOOD, 3200, pickup = "Student union"),
        L("sofia", "North Face winter parka, women's L", "Warm and waterproof. Got me through two winters.", CLOTHING, GOOD, 7000),
        L("sofia", "Leather ankle boots, size 8", "Barely worn, small scuff on the left toe.", CLOTHING, LIKE_NEW, 3800, photos = 1),
        L("sofia", "Rice cooker, 3 cup", "Cooks perfectly, comes with a measuring cup and spatula.", DORM, GOOD, 1500),
        L("sofia", "Acoustic guitar with gig bag", "Starter acoustic with a new set of strings. Tuner included.", OTHER, GOOD, 9500, SOLD, "noah", 3, "Music building"),
        L("sofia", "Polaroid camera + 20 film sheets", "Fun for dorm decorations. Tested and works.", ELECTRONICS, GOOD, 6500),
        L("admin", "Standing desk converter", "Sits on top of any desk, gas-spring lift. Selling the demo unit.", FURNITURE, LIKE_NEW, 8500),
        L("admin", "Spalding basketball", "Indoor/outdoor, good grip left.", SPORTS, FAIR, 1000, photos = 1),
        // tech.edu
        L("dev", "Arduino Uno starter kit", "Complete kit with breadboard, sensors, LEDs and jumper wires.", ELECTRONICS, LIKE_NEW, 2800, SOLD, "ethan", 2, "EE labs hallway"),
        L("dev", "Raspberry Pi 4 (4GB) with case", "Includes power supply and 32GB SD card with OS.", ELECTRONICS, GOOD, 5500),
        L("dev", "Digital multimeter", "Auto-ranging, with test leads and spare fuse.", ELECTRONICS, GOOD, 2200, photos = 1),
        L("dev", "Signals and Systems, Oppenheim 2nd ed", "Used for EE 301. Clean pages.", TEXTBOOKS, GOOD, 3500),
        L("dev", "Soldering station, 60W", "Adjustable temperature, extra tips and stand.", ELECTRONICS, LIKE_NEW, 3600),
        L("dev", "Compact microwave, 700W", "Works well, a few cosmetic scratches.", DORM, FAIR, 2500),
        L("dev", "Broken laptop (for parts)", "Dell XPS, cracked screen, motherboard works. Sold as-is.", ELECTRONICS, POOR, 4000, photos = 1),
        L("ava", "Climbing shoes, men's 10", "Moderate downturn, resoled once. Great for gym bouldering.", SPORTS, FAIR, 3500),
        L("ava", "Mountain bike, 29-inch", "Hardtail with front suspension, new brake pads.", BIKES, GOOD, 32000, photos = 3, pickup = "Tech campus bike shed"),
        L("ava", "Camping tent, 2-person", "Three-season, easy setup, includes footprint.", SPORTS, LIKE_NEW, 7500),
        L("ava", "Bike helmet, size M", "No crashes, adjustable fit. Replaced after one season.", BIKES, GOOD, 2000, photos = 1),
        L("ava", "Drafting table with lamp", "Adjustable-angle table, great for architecture projects.", FURNITURE, GOOD, 9500, RESERVED, "zoe", 2, "Design studio"),
        L("ava", "Concert tickets, Saturday night", "Two general admission tickets. Can't make it any more.", TICKETS, NEW, 8000, photos = 1),
        L("ava", "Desk chair, ergonomic mesh", "Adjustable arms and lumbar support. Smooth casters.", FURNITURE, GOOD, 6000),
    )

    /** A conversation between [buyer] and the listing's seller; lines alternate buyer, seller, buyer, ... */
    data class C(val listing: String, val buyer: String, val lines: List<String>)

    val conversations = listOf(
        C(key("maya", "Campbell Biology, 12th edition"), "noah", listOf(
            "Hi! Is the biology textbook still available?",
            "Yes it is! I can meet at the Science Library lobby tomorrow afternoon.",
            "Perfect, how about 3pm?",
            "3pm works. See you there!",
        )),
        C(key("jordan", "Mechanical keyboard, brown switches"), "liam", listOf(
            "Would you take 40 for the keyboard?",
            "I can do 42, it's basically new.",
        )),
        C(key("sofia", "Vintage denim jacket, size M"), "maya", listOf(
            "Love this jacket. Does it run true to size?",
            "It's a relaxed fit, a size S would also work.",
            "Great, thanks! I'll think it over.",
        )),
        C(key("jordan", "27-inch 1440p monitor"), "sofia", listOf(
            "Hey, I'd like to buy the monitor. I'm starting checkout now.",
            "Awesome, let me know once paid and we can meet.",
        )),
        C(key("dev", "Raspberry Pi 4 (4GB) with case"), "zoe", listOf(
            "Is the Pi still available? Does it include an HDMI cable?",
            "Yes available, no HDMI cable but I can throw in a micro-HDMI adapter.",
        )),
        C(key("ava", "Mountain bike, 29-inch"), "ethan", listOf(
            "Can I test ride the bike before buying?",
            "Sure, we can meet by the bike shed on Thursday.",
            "Thursday at 4 works for me.",
        )),
    )

    /** Ratings for a completed order (the listing's SOLD order): buyer rates seller, optionally seller rates buyer. */
    data class R(val listing: String, val buyerStars: Int, val buyerComment: String, val sellerStars: Int? = null, val sellerComment: String = "")

    val ratings = listOf(
        R(key("maya", "Mini fridge, 3.2 cu ft"), 5, "Fridge works great and Maya was super easy to deal with.", 5, "Prompt and friendly buyer."),
        R(key("jordan", "TI-84 Plus CE graphing calculator"), 5, "Exactly as described, smooth handoff."),
        R(key("sofia", "Acoustic guitar with gig bag"), 4, "Nice guitar for the price. A bit late but communicative.", 5, "Great communication."),
        R(key("dev", "Arduino Uno starter kit"), 5, "Kit had everything and a few extra parts. Thanks Dev!", 4, "Good buyer."),
    )

    /** type LISTING: [target] is a listing key; USER: a user key; MESSAGE: first message of the conversation for that listing key. */
    data class Rep(val reporter: String, val type: String, val target: String, val reason: String, val details: String)

    val reports = listOf(
        Rep("liam", "LISTING", key("admin", "Spalding basketball"), "MISLEADING", "Photos look like they were taken from a different listing."),
        Rep("noah", "USER", "jordan", "SPAM", "Messaged me several times about unrelated items."),
        Rep("maya", "MESSAGE", key("sofia", "Vintage denim jacket, size M"), "HARASSMENT", "Seller's reply felt rude."),
    )

    val favorites = listOf(
        "noah" to key("jordan", "Road bike, 54cm aluminium frame"),
        "noah" to key("maya", "Desk lamp with USB port"),
        "liam" to key("sofia", "North Face winter parka, women's L"),
        "liam" to key("jordan", "Noise-cancelling headphones"),
        "maya" to key("sofia", "Polaroid camera + 20 film sheets"),
        "maya" to key("jordan", "Mechanical keyboard, brown switches"),
        "sofia" to key("priya", "Bookshelf, 5 shelves"),
        "priya" to key("liam", "Full-size futon couch"),
        "jordan" to key("sofia", "Rice cooker, 3 cup"),
        "ethan" to key("ava", "Camping tent, 2-person"),
        "ethan" to key("dev", "Raspberry Pi 4 (4GB) with case"),
        "zoe" to key("ava", "Desk chair, ergonomic mesh"),
        "zoe" to key("dev", "Soldering station, 60W"),
        "ava" to key("dev", "Digital multimeter"),
    )
}
