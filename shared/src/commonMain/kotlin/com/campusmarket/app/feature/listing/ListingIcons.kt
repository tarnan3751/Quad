package com.campusmarket.app.feature.listing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.campusmarket.core.ListingCategory

/*
 * Line icons for categories and listing facts (material-icons-core has none of these). Drawn on a 24-unit grid with a
 * 1.8 stroke so they sit with the outlined Material icons; `Icon` tints the stroke.
 */

private fun lineIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

internal object ListingIcons {
    val Book by lazy {
        lineIcon(
            "Book",
            "M6.5 3H18a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6.5A2.5 2.5 0 0 1 4 18.5v-13A2.5 2.5 0 0 1 6.5 3Z",
            "M4 18.5A2.5 2.5 0 0 1 6.5 16H19",
            "M8.5 7h6",
        )
    }
    val Laptop by lazy {
        lineIcon("Laptop", "M5 5h14a1 1 0 0 1 1 1v9H4V6a1 1 0 0 1 1-1Z", "M2 18.5h20")
    }
    val Sofa by lazy {
        lineIcon(
            "Sofa",
            "M5 10V7.5A2.5 2.5 0 0 1 7.5 5h9A2.5 2.5 0 0 1 19 7.5V10",
            "M3 12a2 2 0 0 1 4 0v2h10v-2a2 2 0 0 1 4 0v5H3Z",
            "M5.5 17v2M18.5 17v2",
        )
    }
    val Shirt by lazy {
        lineIcon("Shirt", "M9 4 4 6.5l1.5 4 2-1V20h9V9.5l2 1 1.5-4L15 4a3 3 0 0 1-6 0Z")
    }
    val Mug by lazy {
        lineIcon(
            "Mug",
            "M4.5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5Z",
            "M15.5 10.5H17a2.5 2.5 0 0 1 0 5h-1.6",
            "M8 3.5v2.5M11.5 3.5v2.5",
        )
    }
    val Bike by lazy {
        lineIcon(
            "Bike",
            "M2 16.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0Z",
            "M15 16.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0Z",
            "M5.5 16.5 9.5 9.5h6l3 7M9.5 9.5l3 7h-7M15.5 9.5l-1-3h2.5M8 7h3",
        )
    }
    val Ticket by lazy {
        lineIcon(
            "Ticket",
            "M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2.5a2.5 2.5 0 0 0 0 5V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2.5a2.5 2.5 0 0 0 0-5Z",
            "M14.5 7.5v1M14.5 11.5v1M14.5 15.5v1",
        )
    }
    val Ball by lazy {
        lineIcon(
            "Ball",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0Z",
            "M3 12h18",
            "M12 3c2.6 2.4 3.8 5.4 3.8 9s-1.2 6.6-3.8 9",
            "M12 3c-2.6 2.4-3.8 5.4-3.8 9s1.2 6.6 3.8 9",
        )
    }
    val Box by lazy {
        lineIcon("Box", "M3.5 7.5 12 3l8.5 4.5v9L12 21l-8.5-4.5Z", "M3.5 7.5 12 12l8.5-4.5", "M12 12v9")
    }
    val Grid by lazy {
        lineIcon(
            "Grid",
            "M4 5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z",
            "M14 5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1Z",
            "M4 15a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z",
            "M14 15a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1Z",
        )
    }
    val Camera by lazy {
        lineIcon(
            "Camera",
            "M3 8.5A1.5 1.5 0 0 1 4.5 7h2.8l1.6-2.5h6.2L16.7 7h2.8A1.5 1.5 0 0 1 21 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5Z",
            "M8.5 12.75a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0Z",
        )
    }
    val Photo by lazy {
        lineIcon(
            "Photo",
            "M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z",
            "m4 16 4.5-4.5 3.5 3.5 2.5-2.5L20 18",
            "M14 8.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0Z",
        )
    }
    val BrokenPhoto by lazy {
        lineIcon(
            "BrokenPhoto",
            "M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z",
            "m4 16 4.5-4.5 3.5 3.5",
            "M3 3l18 18",
        )
    }
    val Sparkle by lazy {
        lineIcon("Sparkle", "M12 3.5 13.9 10.1 20.5 12l-6.6 1.9L12 20.5l-1.9-6.6L3.5 12l6.6-1.9Z")
    }
    val Clock by lazy {
        lineIcon("Clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0Z", "M12 7v5l3 2")
    }
    val Shield by lazy {
        lineIcon("Shield", "M12 3 4.5 6v5.5c0 4.5 3.2 8.2 7.5 9.5 4.3-1.3 7.5-5 7.5-9.5V6Z", "m9 12 2 2 4-4")
    }
    val Block by lazy {
        lineIcon("Block", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0Z", "M5.6 5.6l12.8 12.8")
    }
    val History by lazy {
        lineIcon("History", "M3.5 12a8.5 8.5 0 1 0 2.5-6", "M3.5 4.5V8H7", "M12 8v4l2.5 2")
    }
    val Sort by lazy {
        lineIcon("Sort", "M7 4v16M3.5 16.5 7 20l3.5-3.5", "M17 20V4M13.5 7.5 17 4l3.5 3.5")
    }
}

/** The category's line icon (chips, pickers, facts). */
internal val ListingCategory.icon: ImageVector
    get() = when (this) {
        ListingCategory.TEXTBOOKS -> ListingIcons.Book
        ListingCategory.ELECTRONICS -> ListingIcons.Laptop
        ListingCategory.FURNITURE -> ListingIcons.Sofa
        ListingCategory.CLOTHING -> ListingIcons.Shirt
        ListingCategory.DORM -> ListingIcons.Mug
        ListingCategory.BIKES -> ListingIcons.Bike
        ListingCategory.TICKETS -> ListingIcons.Ticket
        ListingCategory.SPORTS -> ListingIcons.Ball
        ListingCategory.OTHER -> ListingIcons.Box
    }
