package com.campusmarket.server

/*
 * Sanitising user-supplied free text before it is stored and shown to other people.
 */

/**
 * Invisible formatting characters (Unicode category Cf) that can disguise or reorder displayed text: bidi
 * embeddings/overrides/isolates and marks, zero-width spaces, word joiner, BOM, soft hyphen, invisible operators…
 * Zero-width (non-)joiners (needed by emoji sequences and several scripts) and emoji tag characters are allowed.
 */
fun isInvisibleFormatChar(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.FORMAT.toInt() &&
        codePoint != 0x200C && codePoint != 0x200D && codePoint !in 0xE0020..0xE007F

/**
 * Normalises line breaks to `\n` and drops control characters except `\n` and `\t` (Postgres can't even store
 * NUL in a text column). Doesn't trim.
 */
fun String.withoutControlChars(): String {
    val s = replace("\r\n", "\n").replace('\r', '\n')
    if (s.none { Character.isISOControl(it) && it != '\n' && it != '\t' }) return s
    return s.filterNot { Character.isISOControl(it) && it != '\n' && it != '\t' }
}

/** Drops [isInvisibleFormatChar] characters. */
fun String.withoutInvisibleFormatChars(): String {
    if (none { Character.getType(it) == Character.FORMAT.toInt() }) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (!isInvisibleFormatChar(cp)) out.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}
