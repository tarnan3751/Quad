package com.campusmarket.app.feature

import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** `viewModelScope` needs a Main dispatcher on the JVM test target. */
@OptIn(ExperimentalCoroutinesApi::class)
inline fun withMainDispatcher(block: () -> Unit) {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
        block()
    } finally {
        Dispatchers.resetMain()
    }
}

/** Waits (in real time: MockEngine answers on another thread) until the flow's value matches. */
suspend fun <T> StateFlow<T>.awaitMatch(predicate: (T) -> Boolean): T =
    withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

/** The JSON body of a request sent through the real client. */
fun HttpRequestData.jsonBody(): JsonObject {
    val text = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
    return Json.parseToJsonElement(text).jsonObject
}
