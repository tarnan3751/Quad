package com.campusmarket.server.auth

import com.campusmarket.core.Limits
import com.campusmarket.server.ApiException
import com.campusmarket.server.badRequest
import com.campusmarket.server.isInvisibleFormatChar
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import kotlinx.coroutines.CancellationException

/*
 * Input normalisation and validation for account fields. Validators return a user-facing message
 * or null when the value is fine, so callers can collect per-field errors into one 400 VALIDATION.
 */

/** Trimmed and lower-cased. Every lookup and store of an email goes through this. */
fun normalizeEmail(raw: String): String = raw.trim().lowercase()

private const val EMAIL_MAX = 254
private const val EMAIL_LOCAL_MAX = 64
private val EMAIL_LOCAL = Regex("^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*$")
private val DOMAIN_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/** Pragmatic address check for an already [normalizeEmail]-ed value (ASCII only; domain must have a dot). */
fun emailError(email: String): String? {
    if (email.isEmpty()) return "Enter your email address"
    if (email.length > EMAIL_MAX) return "Email address is too long"
    val at = email.lastIndexOf('@')
    if (at <= 0 || at == email.length - 1) return "Enter a valid email address"
    val local = email.substring(0, at)
    val domain = email.substring(at + 1)
    val labels = domain.split('.')
    val ok = local.length <= EMAIL_LOCAL_MAX && EMAIL_LOCAL.matches(local) &&
        labels.size >= 2 && labels.all { DOMAIN_LABEL.matches(it) } && labels.last().any { it.isLetter() }
    return if (ok) null else "Enter a valid email address"
}

/** Domain part of a valid, normalised email. */
fun emailDomain(email: String): String = email.substringAfterLast('@')

/**
 * True if [domain] is covered by one of [suffixes]. A suffix like ".edu" or "edu" matches any domain
 * ending in ".edu"; a full domain like "mit.edu" matches itself and its subdomains, but never "notmit.edu".
 */
fun isAllowedDomain(domain: String, suffixes: List<String>): Boolean = suffixes.any { raw ->
    val s = raw.trim().lowercase().trimStart('.')
    s.isNotEmpty() && (domain == s || domain.endsWith(".$s"))
}

/** Passwords are taken verbatim (never trimmed); only the length is constrained (NIST 800-63B style). */
fun passwordError(password: String): String? = when {
    password.length < Limits.PASSWORD_MIN -> "Password must be at least ${Limits.PASSWORD_MIN} characters"
    password.length > Limits.PASSWORD_MAX -> "Password must be at most ${Limits.PASSWORD_MAX} characters"
    password.isBlank() -> "Password can't be only spaces"
    else -> null
}

private val WHITESPACE_RUN = Regex("\\s+")

/** Trims and collapses internal whitespace runs (including newlines) to single spaces. */
fun normalizeDisplayName(raw: String): String = raw.trim().replace(WHITESPACE_RUN, " ")

fun displayNameError(name: String): String? = when {
    name.length < Limits.DISPLAY_NAME_MIN -> "Name must be at least ${Limits.DISPLAY_NAME_MIN} characters"
    name.length > Limits.DISPLAY_NAME_MAX -> "Name must be at most ${Limits.DISPLAY_NAME_MAX} characters"
    name.any { isForbiddenChar(it, allowNewline = false) } -> "Name contains characters that aren't allowed"
    else -> null
}

/** Trims, normalises line endings, and caps blank-line runs; keeps intentional line breaks. */
fun normalizeBio(raw: String): String =
    raw.replace("\r\n", "\n").replace('\r', '\n').trim().replace(Regex("\n{3,}"), "\n\n")

fun bioError(bio: String): String? = when {
    bio.length > Limits.BIO_MAX -> "Bio must be at most ${Limits.BIO_MAX} characters"
    bio.any { isForbiddenChar(it, allowNewline = true) } -> "Bio contains characters that aren't allowed"
    else -> null
}

/**
 * Control characters, bidi overrides/isolates (used for spoofing, e.g. reversing a name) and the other invisible
 * formatting characters such as zero-width spaces (used to make look-alike names, e.g. of a moderator).
 */
private fun isForbiddenChar(c: Char, allowNewline: Boolean): Boolean =
    (Character.isISOControl(c) && !(allowNewline && c == '\n')) ||
        c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '﻿' ||
        isInvisibleFormatChar(c.code)

/** Throws 400 VALIDATION with [errors] as per-field messages if there are any. */
fun throwIfInvalid(errors: Map<String, String>) {
    if (errors.isNotEmpty()) badRequest(errors.values.first(), errors)
}

/**
 * `call.receive<T>()` that maps every deserialisation problem (malformed JSON, missing fields, wrong
 * content type) to 400 VALIDATION instead of letting it surface as a 415/500.
 */
suspend inline fun <reified T : Any> ApplicationCall.receiveBody(): T =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        badRequest("Malformed request body")
    }
