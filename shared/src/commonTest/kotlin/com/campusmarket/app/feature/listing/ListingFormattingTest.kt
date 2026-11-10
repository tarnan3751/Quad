package com.campusmarket.app.feature.listing

import androidx.compose.ui.text.AnnotatedString
import com.campusmarket.app.ui.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListingFormattingTest {

    @Test
    fun priceInputKeepsDigitsAndAtMostTwoDecimals() {
        assertEquals("1250.56", cleanPriceInput("$1,250.567"))
        assertEquals("12.50", cleanPriceInput("12.5.0"), "a second point is dropped")
        assertEquals("123456", cleanPriceInput("1234567"), "six whole digits is plenty (the limit is $5,000)")
        assertEquals("", cleanPriceInput("abc"))
        assertEquals(125050, Format.parsePriceCents(cleanPriceInput("1,250.50")))
    }

    @Test
    fun priceGroupingAddsThousandsSeparatorsAndMapsTheCursor() {
        fun shown(raw: String) = PriceGrouping.filter(AnnotatedString(raw)).text.text
        assertEquals("5", shown("5"))
        assertEquals("1,250", shown("1250"))
        assertEquals("1,250.5", shown("1250.5"))
        assertEquals("123,456", shown("123456"))
        assertEquals("", shown(""))

        for (raw in listOf("", "5", "1250", "1250.50", "123456.7")) {
            val t = PriceGrouping.filter(AnnotatedString(raw))
            val out = t.text.text
            for (o in 0..raw.length) assertTrue(t.offsetMapping.originalToTransformed(o) in 0..out.length)
            for (o in 0..out.length) assertTrue(t.offsetMapping.transformedToOriginal(o) in 0..raw.length)
            assertEquals(out.length, t.offsetMapping.originalToTransformed(raw.length))
            assertEquals(raw.length, t.offsetMapping.transformedToOriginal(out.length))
        }
    }

    @Test
    fun ageReadsLikeABuyerWouldSayIt() {
        val now = 1_700_000_000_000L
        assertEquals("just now", timeAgo(now - 20_000, now))
        assertEquals("5m ago", timeAgo(now - 5 * 60_000, now))
        assertEquals("2h ago", timeAgo(now - 2 * 3_600_000, now))
        assertEquals("3d ago", timeAgo(now - 3 * 86_400_000L, now))
        assertTrue(!timeAgo(now - 30 * 86_400_000L, now).endsWith("ago"), "older listings show a date")
    }
}
