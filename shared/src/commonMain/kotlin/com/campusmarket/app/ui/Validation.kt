package com.campusmarket.app.ui

import com.campusmarket.app.data.ApiError
import com.campusmarket.core.Limits

/**
 * Client-side form checks mirroring the server (`Limits`). Each returns an error message or null.
 * The server stays authoritative: always also show `ApiError.fields` from a 400 `VALIDATION`.
 */
object Validate {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun email(value: String): String? = when {
        value.isBlank() -> "Enter your campus email"
        !EMAIL.matches(value.trim()) -> "That doesn't look like an email address"
        else -> null
    }

    fun password(value: String): String? = when {
        value.isEmpty() -> "Choose a password (at least ${Limits.PASSWORD_MIN} characters)"
        value.length < Limits.PASSWORD_MIN -> "Use at least ${Limits.PASSWORD_MIN} characters"
        value.length > Limits.PASSWORD_MAX -> "Use at most ${Limits.PASSWORD_MAX} characters"
        else -> null
    }

    fun displayName(value: String): String? {
        val v = value.trim()
        return when {
            v.isEmpty() -> "Enter the name people will see"
            v.length < Limits.DISPLAY_NAME_MIN -> "Use at least ${Limits.DISPLAY_NAME_MIN} characters"
            v.length > Limits.DISPLAY_NAME_MAX -> "Use at most ${Limits.DISPLAY_NAME_MAX} characters"
            else -> null
        }
    }

    fun verificationCode(value: String): String? = when {
        value.length != Limits.VERIFICATION_CODE_LENGTH || !value.all { it.isDigit() } ->
            "Enter the ${Limits.VERIFICATION_CODE_LENGTH}-digit code"
        else -> null
    }

    fun bio(value: String): String? = if (value.length > Limits.BIO_MAX) "Use at most ${Limits.BIO_MAX} characters" else null

    fun listingTitle(value: String): String? {
        val v = value.trim()
        return when {
            v.length < Limits.TITLE_MIN -> "Use at least ${Limits.TITLE_MIN} characters"
            v.length > Limits.TITLE_MAX -> "Use at most ${Limits.TITLE_MAX} characters"
            else -> null
        }
    }

    fun listingDescription(value: String): String? =
        if (value.length > Limits.DESCRIPTION_MAX) "Use at most ${Limits.DESCRIPTION_MAX} characters" else null

    fun pickupLocation(value: String): String? =
        if (value.length > Limits.PICKUP_MAX) "Use at most ${Limits.PICKUP_MAX} characters" else null

    /** Validates raw price input (see `Format.parsePriceCents`). */
    fun price(input: String): String? {
        val cents = Format.parsePriceCents(input) ?: return "Enter a price like 12 or 12.50"
        return when {
            cents < Limits.PRICE_MIN_CENTS -> "Minimum price is ${Format.price(Limits.PRICE_MIN_CENTS)}"
            cents > Limits.PRICE_MAX_CENTS -> "Maximum price is ${Format.price(Limits.PRICE_MAX_CENTS)}"
            else -> null
        }
    }

    fun message(value: String): String? {
        val v = value.trim()
        return when {
            v.isEmpty() -> "Message can't be empty"
            v.length > Limits.MESSAGE_MAX -> "Use at most ${Limits.MESSAGE_MAX} characters"
            else -> null
        }
    }
}

/** Collects non-null errors into the `fieldErrors` map shape used by forms. */
fun fieldErrorsOf(vararg checks: Pair<String, String?>): Map<String, String> =
    checks.mapNotNull { (field, error) -> error?.let { field to it } }.toMap()

/**
 * Splits an [ApiError] for a form: per-field messages (from `fields`, plus [codeToField] for codes that belong to one
 * field, e.g. `EMAIL_TAKEN` → "email") and a form-level message for everything else.
 */
fun ApiError.toFormErrors(codeToField: Map<String, String> = emptyMap()): Pair<Map<String, String>, String?> {
    val field = codeToField[code]
    return when {
        field != null -> (fields + (field to message)) to null
        fields.isNotEmpty() -> fields to null
        else -> emptyMap<String, String>() to message
    }
}
