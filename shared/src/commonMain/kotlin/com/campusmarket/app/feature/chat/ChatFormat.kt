package com.campusmarket.app.feature.chat


/** Inbox previews. Dates and times are in `ui/TimeFormat`. */
internal object ChatFormat {
    private const val PREVIEW_MAX = 120

    /** Same shape as the server's `lastMessagePreview`: whitespace collapsed, at most 120 characters. */
    fun preview(body: String): String {
        val flat = body.trim().replace(Regex("\\s+"), " ")
        return if (flat.length <= PREVIEW_MAX) flat else flat.take(PREVIEW_MAX - 1).trimEnd() + "…"
    }
}
