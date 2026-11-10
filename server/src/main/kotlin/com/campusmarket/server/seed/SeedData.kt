package com.campusmarket.server.seed

import com.campusmarket.core.DisputeReason
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCategory.*
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingCondition.*
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.UserRole
import com.campusmarket.server.seed.AvatarImages.Motif
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Static demo dataset. Keys are stable: ids are derived from them so re-running the seed is a no-op. Times are
 * relative to the moment of seeding ("posted 3 hours ago").
 *
 * The client's live screenshot tests rely on a few facts here: "Campbell Biology" is the newest listing liam@ sees,
 * maya@ has a sold "Mini fridge", liam@ has conversations and sofia@ has a paid order waiting for handoff.
 */
object SeedData {
    const val PASSWORD = "password123"

    data class U(
        val key: String,
        val name: String,
        val domain: String,
        val role: UserRole = UserRole.USER,
        val seller: Boolean = false,
        val bio: String = "",
        /** Illustrated avatar; null keeps the app's initials fallback. */
        val avatar: Motif? = null,
        val joined: Duration,
    ) {
        val email get() = "$key@$domain"
    }

    val users = listOf(
        U("admin", "Alex Rivera", "state.edu", UserRole.ADMIN, seller = true, avatar = Motif.SUNRISE, joined = 400.days,
            bio = "On the Quad team (and a State senior). If something here looks off, or you just have feedback, message me."),
        U("moderator", "Morgan Ellis", "state.edu", UserRole.MODERATOR, joined = 330.days,
            bio = "Student moderator for State. Report anything that breaks the rules and I'll take a look, usually the same day."),
        U("maya", "Maya Chen", "state.edu", seller = true, avatar = Motif.LEAF, joined = 410.days,
            bio = "Bio junior. Studying abroad in Copenhagen next semester, so my whole room has to go by December. Usually around Hartwell or the Science Library."),
        U("jordan", "Jordan Reyes", "state.edu", seller = true, avatar = Motif.PIXELS, joined = 720.days,
            bio = "CS senior, graduating in May and downsizing my desk setup. Easiest to meet at the CS building on weekdays."),
        U("priya", "Priya Patel", "state.edu", seller = true, avatar = Motif.RINGS, joined = 540.days,
            bio = "3rd-year neuroscience PhD. I can usually meet in the Chem building lobby between lab sessions."),
        U("liam", "Liam O'Connor", "state.edu", joined = 300.days,
            bio = "Econ sophomore furnishing my first apartment on a budget. Always looking for desks, lamps and a decent bike."),
        U("sofia", "Sofia Alvarez", "state.edu", seller = true, avatar = Motif.WAVES, joined = 380.days,
            bio = "I thrift a lot and resell what doesn't fit. Everything is washed, smoke-free apartment. Bundle 2+ items and I'll knock a few bucks off."),
        U("noah", "Noah Williams", "state.edu", joined = 80.days,
            bio = "Freshman, probably engineering. Mostly here for textbooks and cheap dorm stuff."),
        U("tyler", "Tyler Grant", "state.edu", joined = 45.days,
            bio = "Junior, finance. Hit me up for tickets to anything."),
        U("dev", "Dev Sharma", "tech.edu", seller = true, avatar = Motif.CIRCUIT, joined = 610.days,
            bio = "EE senior. Dev boards, lab gear and the occasional textbook, all tested before I list them. Pickup at the EE labs."),
        U("ava", "Ava Johnson", "tech.edu", seller = true, avatar = Motif.PEAKS, joined = 480.days,
            bio = "Climbing, biking, camping. Selling gear I've upgraded from. Find me at the bike shed or the climbing wall."),
        U("ethan", "Ethan Brooks", "tech.edu", joined = 260.days,
            bio = "Mech E junior. Quick replies, and I show up when I say I will."),
        U("zoe", "Zoe Kim", "tech.edu", avatar = Motif.ARCHES, joined = 150.days,
            bio = "Architecture sophomore. Always in the design studio, usually covered in foam-core dust."),
    )

    /**
     * A listing. [key] is its stable id and the name of its photo files: `seed/photos/<key>-1.jpg` .. `-<photos>.jpg`
     * (1 is the cover). Its status follows from [orders] (see [statusOf]).
     */
    data class L(
        val key: String,
        val seller: String,
        val title: String,
        val desc: String,
        val cat: ListingCategory,
        val cond: ListingCondition,
        val cents: Int,
        val posted: Duration,
        val photos: Int = 1,
        val pickup: String = "",
    )

    val listings = listOf(
        // ---------------------------------------------------------------------------------------- state.edu
        L("campbell-bio", "maya", "Campbell Biology, 12th edition (hardcover)",
            "Used for BIO 201 and 202, it's the edition both classes still use. Yellow highlighting in the first eight " +
                "chapters (the photo shows about the worst of it), the rest is clean. No torn or loose pages, one corner of " +
                "the cover is a little dented. The bookstore wants $120 for a used copy.\n\nCan meet at the Science Library " +
                "lobby on weekdays.",
            TEXTBOOKS, GOOD, 4500, posted = 18.minutes, pickup = "Science Library lobby"),
        L("mini-fridge", "maya", "Mini fridge, 3.2 cu ft (black)",
            "Kept my drinks and leftovers cold for two years, no weird noises. Has a small freezer shelf that does ice " +
                "cubes fine. Defrosted and wiped down before you get it. It's about 40 lb, so bring a friend or a cart.",
            DORM, GOOD, 6000, posted = 19.days, photos = 2, pickup = "Hartwell Hall front desk"),
        L("desk-lamp", "maya", "Silver desk lamp, bulb included",
            "Metal desk lamp with an adjustable head, warm LED bulb included. Good height for reading in bed or at a desk. " +
                "Only selling because I'm moving out.",
            DORM, LIKE_NEW, 1000, posted = 2.days, pickup = "Hartwell Hall"),
        L("yoga-mat", "maya", "Pink yoga mat, 6 mm",
            "Thick, grippy mat, used for one semester of the rec center's Tuesday vinyasa class. Wiped down and stored " +
                "rolled, no tears or flaking.",
            SPORTS, GOOD, 1200, posted = 6.days, pickup = "Rec center entrance"),
        L("sweater", "maya", "Striped cable-knit sweater, size S",
            "Oatmeal, white and taupe stripes, chunky cable knit and really warm. Slightly oversized fit. Worn maybe five " +
                "times, no pilling. It won't fit in my study-abroad suitcase.",
            CLOTHING, LIKE_NEW, 1800, posted = 2.days + 5.hours, pickup = "Hartwell Hall"),

        L("ti84", "jordan", "TI-84 Plus graphing calculator",
            "Black TI-84 Plus (not the CE). Screen has no dead pixels and every button works. Runs on 4 AAA batteries, " +
                "fresh ones are in it now, and it comes with the slide-on cover. Allowed on the SAT/ACT and every math " +
                "exam here.",
            ELECTRONICS, GOOD, 5500, posted = 14.days, photos = 2, pickup = "Engineering building lobby"),
        L("keyboard", "jordan", "Filco Majestouch TKL, Cherry MX Brown",
            "Tenkeyless mechanical keyboard with MX Brown switches: tactile but not loud enough to annoy a roommate. " +
                "Detachable USB cable. Some shine on the spacebar and WASD keys, otherwise great. Switched to a " +
                "low-profile board, which is the only reason I'm selling.",
            ELECTRONICS, GOOD, 6500, posted = 1.days + 2.hours, pickup = "CS building lobby"),
        L("monitor", "jordan", "ASUS 27\" 1440p monitor (PB278Q)",
            "27-inch 2560x1440 panel with HDMI and DisplayPort. No dead pixels and no backlight bleed that I can see. " +
                "The stand tilts and swivels. Comes with a DisplayPort cable. I don't have the box any more, so bring a " +
                "blanket for the car.",
            ELECTRONICS, LIKE_NEW, 14000, posted = 4.days + 3.hours, pickup = "CS building lobby"),
        L("cs-books", "jordan", "CS textbook bundle: CLRS, Garey & Johnson, Stroustrup + 4 more",
            "Clearing my shelf before I graduate. In the photo: Introduction to Algorithms (CLRS), Computers and " +
                "Intractability, The C++ Programming Language, Foundations of Statistical NLP, Programming Challenges, " +
                "Computer Algorithms and Intro to Bioinformatics Algorithms. A few pencil notes in CLRS, the rest are " +
                "clean.\n\n$60 for all of them, or $15 each if you only need one.",
            TEXTBOOKS, GOOD, 6000, posted = 10.days, pickup = "CS building lobby"),
        L("headphones", "jordan", "Bose QuietComfort 25 noise-cancelling headphones",
            "Wired QC25s with the carrying case and the cable with the inline mic. Noise cancelling runs on one AAA " +
                "battery (lasts about 35 hours) and they still work passively when it dies. I replaced the ear pads in " +
                "August.",
            ELECTRONICS, GOOD, 6500, posted = 3.days, photos = 2, pickup = "CS building lobby"),
        L("road-bike", "jordan", "Red steel Battaglin road bike, 54 cm",
            "Italian steel frame, 54 cm, best for roughly 5'7\" to 5'11\". Set up as a commuter with flat bars and a " +
                "1x drivetrain. New brake pads and tubes in September, rides super smooth. Pedals included, lock not " +
                "included. Test rides are fine.",
            BIKES, GOOD, 34000, posted = 9.days, pickup = "Bike racks outside the student union"),

        L("model-kit", "priya", "Orgo molecular model kit (Prentice Hall)",
            "The model set CHEM 2410 tells you to buy. I counted every piece and nothing's missing. Makes chair " +
                "conformations and stereochemistry so much easier to see. The bookstore sells it for $45.",
            OTHER, LIKE_NEW, 1500, posted = 4.hours, photos = 2, pickup = "Chem building lobby"),
        L("med-books", "priya", "Box of med and neuro textbooks (Kandel, Robbins, Lodish...)",
            "A friend left these with me after finishing med school. Principles of Neural Science, Robbins Basic " +
                "Pathology, Molecular Cell Biology, Stedman's, plus Case Files, Blueprints and a couple of USMLE review " +
                "books. Some have highlighting, all are complete. Great for pre-med or anyone taking neuro.\n\n$80 for " +
                "the whole box, I'd rather not split it up.",
            TEXTBOOKS, FAIR, 8000, posted = 12.days, pickup = "Chem building lobby"),
        L("kettle", "priya", "Bosch glass electric kettle, 1.7 L",
            "Boils a full kettle in about three minutes and switches itself off. Descaled last week, no limescale. My " +
                "new building doesn't allow kettles, so it has to go.",
            DORM, LIKE_NEW, 1800, posted = 4.days, pickup = "Kessler Library steps"),
        L("casio", "priya", "Casio fx-991CW ClassWiz calculator",
            "Scientific calculator that's approved for most STEM exams. Solar plus battery, comes with the hard slide " +
                "cover. Bought it for a stats course I ended up dropping, so it's barely used.",
            ELECTRONICS, LIKE_NEW, 1500, posted = 1.hours + 40.minutes, pickup = "Chem building lobby"),
        L("coffee-maker", "priya", "Hamilton Beach 5-cup coffee maker",
            "Small drip coffee maker, perfect for a dorm or a tiny kitchen. Programmable clock and auto shut-off. Glass " +
                "carafe has no chips. I switched to a French press.",
            DORM, GOOD, 1200, posted = 3.days + 20.hours, pickup = "Kessler Library steps"),

        L("futon", "liam", "Red futon sofa (folds into a bed)",
            "Red microsuede futon that folds flat into a full-size bed. Metal legs, no stains or tears. Moving into a " +
                "furnished place in January. You'll need a car or a friend with a hatchback, but the arms come off to " +
                "make it smaller.",
            FURNITURE, GOOD, 9000, posted = 11.days, pickup = "Greenfield Apartments"),
        L("football-tix", "liam", "2 tickets: State vs Tech, Saturday, section 112",
            "Two seats together in section 112, row 18, home side lower bowl. Kickoff is 3:30 on Saturday. I'll transfer " +
                "both in the ticketing app right after checkout. Family's visiting that weekend so I can't go. Price is " +
                "for the pair.",
            TICKETS, NEW, 7000, posted = 7.hours),

        L("denim-jacket", "sofia", "Vintage light-wash denim jacket, M",
            "Classic trucker cut in a light wash that's broken in just right. Thrifted, washed, no rips or stains. Fits " +
                "a women's M or men's S. Pit to pit is 20\".",
            CLOTHING, GOOD, 2800, posted = 3.days + 6.hours, pickup = "Student union"),
        L("puffer-jacket", "sofia", "Vintage Polo Ralph Lauren down jacket, L",
            "90s Polo down jacket with the Aztec print. Super warm, the zipper and snaps all work, and the down is " +
                "evenly spread with no flat spots. Men's L, roomy. One of my favorite finds this year.",
            CLOTHING, GOOD, 8500, posted = 5.days, pickup = "Student union"),
        L("chelsea-boots", "sofia", "R.M. Williams Chelsea boots, size 9",
            "Black leather Comfort Craftsman boots, men's US 9 (about a women's 10.5). Resoled once, the uppers have " +
                "normal creasing. These retail for over $500. Polished them before taking the photos.",
            CLOTHING, GOOD, 13500, posted = 6.days + 12.hours, photos = 2, pickup = "Student union"),
        L("rice-cooker", "sofia", "Rival 3-cup rice cooker",
            "Makes up to 3 cups of cooked rice and switches to keep-warm by itself. The non-stick pot has no scratches. " +
                "Measuring cup included.",
            DORM, GOOD, 1200, posted = 13.days, pickup = "Student union"),
        L("guitar", "sofia", "Walden D310e acoustic-electric guitar",
            "Dreadnought with a built-in tuner and pickup, so you can plug it into an amp. Plays great, low action, new " +
                "strings in October. Comes with a gig bag. The stand in the photos isn't included.",
            OTHER, GOOD, 15500, posted = 23.days, photos = 3, pickup = "Music building"),
        L("polaroid", "sofia", "Polaroid Supercolor 1000 instant camera",
            "Vintage Land camera with the flash bar. Tested last month with a fresh pack of film and the photos came " +
                "out. Film not included. Great for dorm walls.",
            ELECTRONICS, GOOD, 4500, posted = 8.days, photos = 2, pickup = "Student union"),

        L("standing-desk", "admin", "Standing desk + bar stool",
            "Fixed-height standing desk (about 42\") with a shelf underneath, plus the padded stool. Monitors not " +
                "included. It comes apart with an Allen key and I'll help you load it.",
            FURNITURE, GOOD, 7000, posted = 16.days, pickup = "Maple St, 5 minutes from the quad"),
        L("basketball", "admin", "Spalding NBA indoor/outdoor basketball",
            "Well loved, but it still holds air fine. The grip is worn smooth in a couple of spots. Good enough for " +
                "pickup games at the rec.",
            SPORTS, FAIR, 800, posted = 24.days, pickup = "Rec center entrance"),

        L("tennis", "noah", "Wilson tennis racket + ball",
            "Bought it for the tennis club and went twice. Grip size 3, still has the original strings. Includes one " +
                "ball (lol).",
            SPORTS, LIKE_NEW, 3500, posted = 18.days, pickup = "Rec center entrance"),

        L("concert-tix", "tyler", "2 GA tickets, Saturday show at the Fillmore",
            "Two general admission tickets for Saturday night, doors at 7. Can't go anymore. Message me.",
            TICKETS, NEW, 16000, posted = 1.days + 3.hours),

        // ---------------------------------------------------------------------------------------- tech.edu
        L("arduino-kit", "dev", "Arduino experimentation kit (ARDX), complete",
            "Arduino board (Duemilanove, same pinout as the Uno), breadboard, servo, DC motor, LEDs, sensors, jumper " +
                "wires, USB cable and the printed project guide, all in the original case. I did the first few projects " +
                "for ECE 110 and everything still works.",
            ELECTRONICS, LIKE_NEW, 2800, posted = 21.days, photos = 2, pickup = "EE labs hallway, 2nd floor"),
        L("rpi4", "dev", "Raspberry Pi 4 Model B, 4 GB",
            "Comes with the official USB-C power supply and a 32 GB SD card with Raspberry Pi OS on it. Ran Pi-hole " +
                "for a year, never overclocked. No case.",
            ELECTRONICS, GOOD, 4500, posted = 1.days + 18.hours, photos = 2, pickup = "EE labs hallway, 2nd floor"),
        L("multimeter", "dev", "Aneng AN870 multimeter with case",
            "True RMS, auto-ranging, 19999 counts. Includes the zip case, two sets of test leads and alligator clips. " +
                "Upgraded to a Fluke for lab.",
            ELECTRONICS, LIKE_NEW, 2200, posted = 2.days + 3.hours, photos = 2, pickup = "EE labs hallway, 2nd floor"),
        L("gened-books", "dev", "Humanities gen-ed books, 8 hardcovers",
            "Everything from the HUM 101 reading list, finally done with gen-eds. All hardcover, a few have pencil " +
                "notes in the margins. $20 for the stack.",
            TEXTBOOKS, FAIR, 2000, posted = 11.days + 7.hours, pickup = "EE labs hallway, 2nd floor"),
        L("soldering", "dev", "2-in-1 soldering + hot air station (Accta 301)",
            "Soldering iron and hot air rework gun in one unit, with the iron stand. Used for one capstone project. " +
                "Comes in the original box with a couple of spare tips.",
            ELECTRONICS, LIKE_NEW, 4000, posted = 7.days, pickup = "EE labs hallway, 2nd floor"),
        L("microwave", "dev", "Panasonic microwave",
            "Works fine and the turntable spins. A few cosmetic scratches on top, the inside is clean. Leaving my " +
                "apartment, so it needs a new home.",
            DORM, FAIR, 2500, posted = 15.days, pickup = "Engineering quad"),
        L("parts-laptop", "dev", "HP ProBook for parts (screen damage)",
            "The panel has dead spots (see photo) but everything else works: boots, keyboard and trackpad are fine, " +
                "battery holds about 2 hours. Works with an external monitor. Selling as-is for parts or a project.",
            ELECTRONICS, POOR, 6000, posted = 5.hours, pickup = "EE labs hallway, 2nd floor"),

        L("climbing-shoes", "ava", "Velcro climbing shoes, EU 42",
            "Red velcro shoes with a moderate downturn, comfy for long gym sessions. Plenty of rubber left, the toes are " +
                "a bit worn. Fit like a men's US 9 to 9.5.",
            SPORTS, FAIR, 3000, posted = 4.days + 2.hours, pickup = "Climbing wall, rec center"),
        L("mtb", "ava", "Specialized Rockhopper hardtail, size M",
            "Red Rockhopper with front suspension and disc brakes. New brake pads and chain last month, the tires have " +
                "lots of tread. Fits about 5'6\" to 5'11\". Happy to do test rides by the bike shed.",
            BIKES, GOOD, 32000, posted = 3.days + 2.hours, pickup = "Tech campus bike shed"),
        L("tent", "ava", "2-person dome tent with rainfly",
            "Three-season dome tent with all the poles, stakes and the rainfly. No holes and the zippers work. Pitched " +
                "it maybe ten times. Sleeps two cozy or one with gear.",
            SPORTS, GOOD, 5500, posted = 9.days + 4.hours, pickup = "Tech campus bike shed"),
        L("helmet", "ava", "White bike helmet, size M (54-58 cm)",
            "No crashes, ever. Dial adjustment at the back and the pads were just washed. Replaced it with a MIPS one.",
            BIKES, GOOD, 1500, posted = 1.days + 1.hours, photos = 2, pickup = "Tech campus bike shed"),
        L("drafting-table", "ava", "Vintage drafting table on a hydraulic stand",
            "1960s Swedish drafting table on a cast-iron hydraulic stand. It tilts from flat to fully vertical and " +
                "raises smoothly. Big wooden top, about 47\" x 31\". Heavy, so bring a friend and a car.",
            FURNITURE, GOOD, 12000, posted = 7.days + 5.hours, pickup = "Design studio, Building 7"),
        L("desk-chair", "ava", "Sihoo M57 ergonomic mesh chair",
            "Adjustable headrest, armrests and lumbar support (that's the knob in the last photo). The mesh is intact, " +
                "no squeaks, and the height and tilt lock both work. Only assembled once.",
            FURNITURE, GOOD, 8500, posted = 6.days + 8.hours, photos = 3, pickup = "Tech campus bike shed"),

        L("plant-cuttings", "zoe", "Rooted Monstera adansonii cuttings",
            "Rooted in water with 2-3 leaves each, ready to pot. My plant is taking over the studio so I'm sharing. $8 " +
                "each, or 3 for $20.",
            OTHER, NEW, 800, posted = 35.minutes, pickup = "Design studio, Building 7"),
        L("wacom", "zoe", "Wacom Intuos Pro (medium) + pen",
            "Drawing tablet with the pen and the pen stand with spare nibs. Works over USB or Bluetooth. I moved to an " +
                "iPad for sketching. Small scuffs on the surface, doesn't affect tracking.",
            ELECTRONICS, GOOD, 14000, posted = 9.hours, pickup = "Design studio, Building 7"),

        L("longboard", "ethan", "Wooden pintail longboard, 40\"",
            "Maple pintail with red wheels. Rolls smooth, the bearings were cleaned last month. Great for getting " +
                "across campus. No cracks.",
            BIKES, GOOD, 4500, posted = 5.days + 4.hours, pickup = "Engineering quad"),
    )

    fun listing(key: String) = listings.first { it.key == key }
    fun user(key: String) = users.first { it.key == key }

    data class Dispute(val reason: DisputeReason, val details: String, val afterPayment: Duration)

    /**
     * An order on [listing], placed [placed] ago (paid a few minutes later unless CANCELLED). COMPLETED orders were
     * handed off [handoff] after payment.
     */
    data class O(
        val listing: String,
        val buyer: String,
        val status: OrderStatus,
        val placed: Duration,
        val handoff: Duration = 20.hours,
        val dispute: Dispute? = null,
    )

    val orders = listOf(
        O("mini-fridge", "liam", OrderStatus.COMPLETED, placed = 15.days, handoff = 22.hours),
        O("ti84", "noah", OrderStatus.COMPLETED, placed = 12.days, handoff = 2.hours),
        O("guitar", "noah", OrderStatus.COMPLETED, placed = 19.days, handoff = 1.days),
        O("arduino-kit", "ethan", OrderStatus.COMPLETED, placed = 17.days, handoff = 5.hours),
        // awaiting handoff: paid hours ago, well inside the 72 h auto-release window
        O("monitor", "sofia", OrderStatus.PAID, placed = 5.hours + 10.minutes),
        O("tent", "ethan", OrderStatus.PAID, placed = 20.hours),
        O("drafting-table", "zoe", OrderStatus.DISPUTED, placed = 30.hours, dispute = Dispute(
            DisputeReason.SELLER_NO_SHOW,
            "We agreed on 4pm at the design studio in Building 7. I waited 30 minutes, messaged twice and never heard " +
                "back. I've already paid for the table.",
            afterPayment = 24.hours,
        )),
        // the buyer test-rode it and changed his mind; the seller refunded
        O("road-bike", "liam", OrderStatus.REFUNDED, placed = 5.days),
        // checkout started and never finished
        O("headphones", "noah", OrderStatus.CANCELLED, placed = 2.days),
    )

    /** SOLD after a completed order, RESERVED while one is live, otherwise ACTIVE. */
    fun statusOf(l: L): ListingStatus {
        val statuses = orders.filter { it.listing == l.key }.map { it.status }
        return when {
            OrderStatus.COMPLETED in statuses -> ListingStatus.SOLD
            statuses.any { it == OrderStatus.PAID || it == OrderStatus.DISPUTED || it == OrderStatus.PENDING_PAYMENT } -> ListingStatus.RESERVED
            else -> ListingStatus.ACTIVE
        }
    }

    /** A message [at] after the conversation started. */
    data class M(val fromBuyer: Boolean, val at: Duration, val body: String)

    private fun b(at: Duration, body: String) = M(true, at, body)
    private fun s(at: Duration, body: String) = M(false, at, body)

    /**
     * A conversation between [buyer] and the listing's seller that started [started] ago. When [unread], the
     * recipient of the last message hasn't opened the thread since they last wrote in it.
     */
    data class C(val listing: String, val buyer: String, val started: Duration, val messages: List<M>, val unread: Boolean = false)

    val conversations = listOf(
        C("campbell-bio", "noah", started = 12.minutes, unread = true, messages = listOf(
            b(0.minutes, "Hi! Is this still available? I'm in BIO 201 next semester"),
            s(2.minutes, "Yes! It's the edition 201 uses. The highlighting is only in the first few chapters"),
            b(5.minutes, "Would you take $40? I could meet at the Science Library tomorrow"),
        )),
        C("keyboard", "liam", started = 20.hours, unread = true, messages = listOf(
            b(0.minutes, "Would you take $55 for the Filco?"),
            s(35.minutes, "I could do $60, it comes with the original box and a keycap puller"),
            b(41.minutes, "Deal. Can I grab it tomorrow after 2?"),
            s(19.hours, "Sure, I'm in the CS building lobby 2-4. Message me when you get there"),
        )),
        C("concert-tix", "liam", started = 22.hours, messages = listOf(
            b(0.minutes, "Hey, are these still available? Can I buy them through checkout?"),
            s(8.minutes, "yeah but the app takes a cut. venmo me $150 and ill send them tonight"),
            b(15.minutes, "I'd rather pay through the app, that's kind of the point of it"),
            s(21.minutes, "then its $175 lol"),
        )),
        C("denim-jacket", "maya", started = 1.days + 4.hours, messages = listOf(
            b(0.minutes, "Love this jacket! What are the measurements?"),
            s(22.minutes, "Pit to pit is 20\" and it's about 22\" long from the collar. Fits like a women's M"),
            b(30.minutes, "Hmm, I'm usually an S. Would it be too boxy on me?"),
            s(48.minutes, "It'd be a little oversized, but that's kind of the look with these. Happy to let you try it on at the union"),
        )),
        C("monitor", "sofia", started = 30.hours, messages = listOf(
            b(0.minutes, "Hi! Does the monitor work with a MacBook Air?"),
            s(40.minutes, "Yep, USB-C to DisplayPort or HDMI, 1440p works fine. I have a USB-C to DP cable I can throw in"),
            b(25.hours, "Perfect, just paid through checkout"),
            s(25.hours + 12.minutes, "Got it! I'm in the CS lobby tomorrow 10-12, does that work?"),
            b(25.hours + 15.minutes, "10:30 works, see you then"),
        )),
        C("futon", "priya", started = 3.days, messages = listOf(
            b(0.minutes, "Is the futon still available? Will it fit up a narrow staircase?"),
            s(2.hours, "It is! The arms come off and the frame folds, so it should"),
            b(2.hours + 10.minutes, "Perfect, let me check with my roommate and I'll get back to you tonight"),
        )),
        C("road-bike", "liam", started = 6.days, messages = listOf(
            b(0.minutes, "Is the road bike still for sale? I'm 5'9\""),
            s(30.minutes, "Yes, the 54 should fit you fine"),
            b(1.days + 2.minutes, "Just paid! Can I pick it up Saturday morning?"),
            s(1.days + 20.minutes, "Saturday at 11 at the racks outside the union works"),
            b(1.days + 19.hours, "Took it around the block and the reach is honestly too long for me, sorry. Could you cancel it?"),
            s(1.days + 19.hours + 40.minutes, "No worries, it happens. Refunding you now, it can take a few days to show up"),
        )),
        C("mini-fridge", "liam", started = 16.days, messages = listOf(
            b(0.minutes, "Hi! Is the fridge still available? Will it fit in a hatchback?"),
            s(20.minutes, "It will, it's about 19\" wide. Weighs around 40 lb"),
            b(1.days + 5.minutes, "Just bought it. Does Sunday afternoon work for pickup?"),
            s(1.days + 10.minutes, "Sunday at 2 at the Hartwell front desk 👍"),
        )),
        C("ti84", "noah", started = 12.days + 3.hours, messages = listOf(
            b(0.minutes, "Hi, is the TI-84 still available?"),
            s(10.minutes, "Yes! Works great, fresh batteries in it"),
            b(14.minutes, "Awesome. Could we meet today? I have a calc quiz Friday"),
            s(25.minutes, "Engineering lobby at 5?"),
            b(27.minutes, "See you there!"),
        )),
        C("guitar", "noah", started = 20.days, messages = listOf(
            b(0.minutes, "Does the guitar come with a case?"),
            s(1.hours, "Just a gig bag, no hard case. The stand in the photos isn't included either"),
            b(1.hours + 5.minutes, "That's fine. Could you do $140?"),
            s(1.hours + 30.minutes, "Meet me at $155? New strings went on last month"),
            b(1.hours + 32.minutes, "Deal 🎸"),
        )),
        C("rpi4", "zoe", started = 1.days, unread = true, messages = listOf(
            b(0.minutes, "Is the Pi still available? Does it come with an HDMI cable?"),
            s(1.hours, "Still here! No cable, but I can throw in a micro-HDMI adapter"),
            b(1.hours + 6.minutes, "Oh nice. Would you do $40?"),
        )),
        C("mtb", "ethan", started = 2.days, messages = listOf(
            b(0.minutes, "Can I test ride it before buying? I'm 5'10\""),
            s(15.minutes, "Of course, the M should fit you. I'm at the bike shed Thursday 4-6"),
            b(20.minutes, "Thursday at 4:30 works"),
            s(22.minutes, "Cool, it's the red one next to the pump station"),
        )),
        C("tent", "ethan", started = 1.days + 6.hours, messages = listOf(
            b(0.minutes, "Does the tent come with stakes and a footprint?"),
            s(20.minutes, "Stakes and the rainfly yes, no footprint"),
            b(10.hours + 6.minutes, "Cool, just paid. Can I grab it before my trip on Saturday?"),
            s(10.hours + 20.minutes, "Yep, bike shed tomorrow at 5?"),
            b(10.hours + 22.minutes, "👍"),
        )),
        C("drafting-table", "zoe", started = 2.days, unread = true, messages = listOf(
            b(0.minutes, "Hi! Is the drafting table still available? I'm in the design studio pretty much every day"),
            s(25.minutes, "Yes! Heads up, the base is cast iron so it's heavy"),
            b(18.hours + 6.minutes, "My roommate has a car. Just paid, can we do tomorrow at 4 at the studio?"),
            s(18.hours + 30.minutes, "Tomorrow at 4 works"),
            b(41.hours + 5.minutes, "I'm at the studio, are you close?"),
            b(41.hours + 35.minutes, "Waited half an hour, heading home. Please let me know what happened"),
        )),
    )

    /** Ratings for a completed order: buyer rates seller, optionally seller rates buyer. */
    data class R(val listing: String, val buyerStars: Int, val buyerComment: String, val sellerStars: Int? = null, val sellerComment: String = "")

    val ratings = listOf(
        R("mini-fridge", 5, "Fridge was spotless and already cold an hour after I plugged it in. Maya even helped carry it to my car.",
            5, "Right on time and brought a dolly. Easy."),
        R("ti84", 4, "Calculator works perfectly, exactly as described. Had to move our meetup once but he replied fast.",
            5, "Quick and friendly. Good luck on the quiz!"),
        R("guitar", 5, "Sounds even better in person and she put new strings on before I picked it up.",
            4, "Nice buyer. About 15 minutes late but messaged ahead."),
        R("arduino-kit", 3, "Everything listed was there, but two of the LEDs were dead and the jumper wires were a tangled mess. Fine for the price."),
    )

    /** type LISTING: [target] is a listing key; USER: a user key; MESSAGE: the seller's first message in that listing's conversation. */
    data class Rep(val reporter: String, val type: String, val target: String, val reason: String, val details: String, val ago: Duration)

    val reports = listOf(
        Rep("liam", "MESSAGE", "concert-tix", "SCAM",
            "Asked me to pay over Venmo instead of checkout, then raised the price to $175 when I said no.", 21.hours),
        Rep("maya", "LISTING", "concert-tix", "SCAM",
            "Pretty sure these are the same tickets someone posted in a class group chat for a different date. Seller won't use checkout.", 14.hours),
        Rep("jordan", "USER", "tyler", "HARASSMENT",
            "Sent me a string of messages calling me a scammer after I turned down his lowball offer on my monitor.", 2.days),
    )

    data class F(val user: String, val listing: String, val ago: Duration)

    val favorites = listOf(
        F("noah", "desk-lamp", 1.days),
        F("noah", "kettle", 2.days),
        F("noah", "casio", 50.minutes),
        F("liam", "puffer-jacket", 3.days),
        F("liam", "headphones", 2.days + 6.hours),
        F("liam", "standing-desk", 9.days),
        F("maya", "polaroid", 5.days),
        F("maya", "denim-jacket", 1.days + 5.hours),
        F("maya", "coffee-maker", 3.days),
        F("sofia", "futon", 10.days),
        F("priya", "futon", 3.days + 2.hours),
        F("priya", "desk-lamp", 1.days + 12.hours),
        F("jordan", "rice-cooker", 12.days),
        F("jordan", "chelsea-boots", 4.days),
        F("ethan", "mtb", 2.days + 1.hours),
        F("ethan", "rpi4", 1.days + 10.hours),
        F("zoe", "desk-chair", 6.days),
        F("zoe", "soldering", 3.days),
        F("ava", "multimeter", 1.days + 20.hours),
        F("dev", "desk-chair", 5.days),
    )
}
