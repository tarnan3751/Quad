package com.campusmarket.app.feature.auth

import kotlinx.serialization.Serializable

// Signed-out destinations (auth NavHost). Owner: client foundation.

@Serializable
data object WelcomeRoute

@Serializable
data object RegisterRoute

/** [devCode] is the server's dev-mode code from `RegisterResponse.devCode`, prefilled when present. */
@Serializable
data class VerifyEmailRoute(val email: String, val devCode: String? = null)

/** [notice] is an optional message shown above the form (e.g. why the user was sent back here). */
@Serializable
data class LoginRoute(val email: String? = null, val notice: String? = null)
