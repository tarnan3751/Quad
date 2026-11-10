package com.campusmarket.server

import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import java.util.UUID

/** Throw from anywhere in a request handler; rendered as an [ErrorResponse] with the given status. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val fields: Map<String, String> = emptyMap(),
) : RuntimeException(message)

fun badRequest(message: String, fields: Map<String, String> = emptyMap()): Nothing =
    throw ApiException(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, message, fields)

fun invalidField(field: String, message: String): Nothing = badRequest(message, mapOf(field to message))
fun unauthorized(message: String = "Authentication required", code: String = ErrorCodes.UNAUTHORIZED): Nothing =
    throw ApiException(HttpStatusCode.Unauthorized, code, message)

fun forbidden(message: String = "Not allowed", code: String = ErrorCodes.FORBIDDEN): Nothing =
    throw ApiException(HttpStatusCode.Forbidden, code, message)

fun notFound(what: String = "Resource"): Nothing =
    throw ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "$what not found")

fun conflict(message: String, code: String = ErrorCodes.CONFLICT): Nothing =
    throw ApiException(HttpStatusCode.Conflict, code, message)

/** Parses a path/body id; a malformed id is reported as 404 for [what]. */
fun String?.asUuid(what: String = "Resource"): UUID = this?.toUuidOrNull() ?: notFound(what)

fun Application.installErrorHandling() {
    val log = LoggerFactory.getLogger("Errors")
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ErrorResponse(e.code, e.message, e.fields))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorCodes.VALIDATION, "Malformed request: ${e.message ?: "bad request"}"))
        }
        exception<io.ktor.server.plugins.ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorCodes.VALIDATION, "Malformed request body"))
        }
        exception<io.ktor.server.plugins.UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse(ErrorCodes.VALIDATION, "Unsupported content type"))
        }
        exception<Throwable> { call, e ->
            // path only: query strings can carry secrets (the websocket's ?token=)
            log.error("Unhandled error on ${call.request.local.method.value} ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorCodes.INTERNAL, "Something went wrong"))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCodes.NOT_FOUND, "Not found"))
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ErrorCodes.RATE_LIMITED, "Too many requests, slow down"))
        }
    }
}
