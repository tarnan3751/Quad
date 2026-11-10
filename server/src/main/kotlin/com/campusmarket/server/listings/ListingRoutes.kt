package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OkResponse
import com.campusmarket.core.UpdateListingRequest
import com.campusmarket.server.AUTH_JWT
import com.campusmarket.server.AppDeps
import com.campusmarket.server.asUuid
import com.campusmarket.server.badRequest
import com.campusmarket.server.user
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

/** Entry point for the listings feature; called once from Application.module. See docs/CONTRACT.md. */
fun Route.listingRoutes(deps: AppDeps) {
    val listings = ListingService(deps)
    val browse = ListingBrowse(deps)

    authenticate(AUTH_JWT) {
        route(ApiRoutes.LISTINGS) {
            get {
                call.respond(browse.search(call.user, SearchParams.parse(call.request.queryParameters), call.pageParams()))
            }
            post {
                val req = call.receiveJson<CreateListingRequest>()
                call.respond(HttpStatusCode.Created, listings.create(call.user, req))
            }
            route("/{id}") {
                get {
                    call.respond(listings.get(call.user, call.listingId()))
                }
                patch {
                    val id = call.listingId()
                    val req = call.receiveJson<UpdateListingRequest>()
                    call.respond(listings.update(call.user, id, req))
                }
                delete {
                    listings.delete(call.user, call.listingId())
                    call.respond(OkResponse())
                }
                route("/favorite") {
                    post {
                        listings.favorite(call.user, call.listingId())
                        call.respond(OkResponse())
                    }
                    delete {
                        listings.unfavorite(call.user, call.listingId())
                        call.respond(OkResponse())
                    }
                }
            }
        }

        get(ApiRoutes.MY_FAVORITES) {
            call.respond(browse.favorites(call.user, call.pageParams()))
        }
        get(ApiRoutes.MY_LISTINGS) {
            val statuses = call.request.queryParameters.enumSet<ListingStatus>("status")
            call.respond(browse.mine(call.user, statuses, call.pageParams()))
        }
        get("${ApiRoutes.BASE}/users/{id}/listings") {
            val sellerId = call.parameters["id"].asUuid("User")
            call.respond(browse.bySeller(call.user, sellerId, call.pageParams()))
        }
        get(ApiRoutes.CATEGORIES) {
            call.respond(browse.categories(call.user))
        }
    }
}

private fun ApplicationCall.listingId(): UUID = parameters["id"].asUuid("Listing")

/** Like `receive<T>()`, but a missing/non-JSON Content-Type is a 400 rather than an unhandled exception (500). */
private suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T = try {
    receive<T>()
} catch (_: ContentTransformationException) {
    badRequest("Expected a JSON request body (Content-Type: application/json)")
}
