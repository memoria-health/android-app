package com.memoria.caregiver.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.http.path
import io.ktor.utils.io.errors.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * The HTTP spine. Port of the plumbing at the bottom of
 * `Services/NemaraAPIClient.swift` (makeRequest / send* / mapStatus).
 *
 * Kept separate from the endpoint methods so the status mapping — which is the
 * part with real behavior in it — can be unit-tested without a server.
 *
 * Every request sends `Accept: application/json` and, when a token is
 * available, `Authorization: Bearer …`. The token is fetched per request rather
 * than held, because Supabase refreshes it underneath us; caching it here would
 * send a stale JWT after a refresh.
 */
class HttpTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val tokenProvider: suspend () -> String?,
) {

    suspend inline fun <reified T> get(path: String, query: Map<String, String> = emptyMap()): T =
        send(HttpMethod.Get, path, query, null)

    suspend inline fun <reified T> post(path: String, body: Any? = null): T =
        send(HttpMethod.Post, path, emptyMap(), body)

    suspend inline fun <reified T> patch(path: String, body: Any? = null): T =
        send(HttpMethod.Patch, path, emptyMap(), body)

    suspend fun delete(path: String, query: Map<String, String> = emptyMap()) {
        sendVoid(HttpMethod.Delete, path, query, null)
    }

    suspend fun postVoid(path: String, body: Any? = null) {
        sendVoid(HttpMethod.Post, path, emptyMap(), body)
    }

    /** Runs the request and maps failures, without decoding a body. */
    suspend fun sendVoid(
        method: HttpMethod,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: Any? = null,
    ) {
        val response = execute(method, path, query, body)
        mapStatus(response.status.value, response.bodyAsTextOrEmpty())
    }

    suspend inline fun <reified T> send(
        method: HttpMethod,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: Any? = null,
    ): T {
        val response = execute(method, path, query, body)
        val text = response.bodyAsTextOrEmpty()
        mapStatus(response.status.value, text)
        return try {
            NemaraJson.json.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            // iOS logs the failing body in DEBUG and rethrows as .decoding.
            // Deliberately no log here: a response body can carry patient names
            // and photo URLs, and Android logcat is readable by anything with
            // READ_LOGS on a rooted device. The type name and the parser's
            // message are enough to locate the field.
            throw ApiError.Decoding(underlying = e.message ?: "unknown decoding failure")
        }
    }

    @PublishedApi
    internal suspend fun execute(
        method: HttpMethod,
        path: String,
        query: Map<String, String>,
        body: Any?,
    ): HttpResponse = try {
        client.request(baseUrl) {
            this.method = method
            url { u ->
                u.path(path.removePrefix("/"))
                query.forEach { (k, v) -> u.parameters.append(k, v) }
            }
            accept(ContentType.Application.Json)
            tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) {
                // Only a value we are asking Ktor to serialise gets the JSON
                // content type. An OutgoingContent — a multipart photo or a
                // batch of enrollment frames — carries its own type, and its
                // boundary parameter is generated, so declaring JSON here would
                // be describing the body wrongly.
                //
                // It worked before this line existed, but only by accident:
                // Ktor's header merge happens to prefer `content.contentType`
                // over a request header, so the multipart type won. That is an
                // implementation detail of the engine, invisible at this call
                // site, and it would have failed silently as a 422 the day it
                // changed. Two separate uploads depend on it.
                if (body !is OutgoingContent) {
                    contentType(ContentType.Application.Json)
                }
                setBody(body)
            }
        }
    } catch (e: IOException) {
        // Swift maps notConnectedToInternet / networkConnectionLost / timedOut
        // to .offline and everything else to .other. On Ktor those all surface
        // as IOException subtypes, so the whole transport-failure class maps to
        // Offline. The copy is the same either way, and treating a timeout as
        // "you appear to be offline" is what iOS already does.
        throw ApiError.Offline
    }

    @PublishedApi
    internal suspend fun HttpResponse.bodyAsTextOrEmpty(): String =
        try { bodyAsText() } catch (_: Exception) { "" }

    companion object {

        /**
         * FastAPI emits two error shapes: `{"detail": "..."}` for HTTPException
         * paths, and `{"detail": [{loc, msg, type}, …]}` for pydantic's 422.
         * Both are tried and neither is required.
         */
        @PublishedApi
        internal fun mapStatus(status: Int, body: String) {
            if (status in 200..299) return

            val simple = decodeQuietly(SimpleDetail.serializer(), body)?.detail
            val validation = decodeQuietly(ValidationEnvelope.serializer(), body)?.detail

            throw when (status) {
                400 -> ApiError.BadRequest(simple ?: "Bad request.")
                401 -> ApiError.Unauthorized
                403 -> ApiError.Forbidden
                404 -> ApiError.NotFound
                409 -> ApiError.Conflict(simple ?: "conflict")
                422 -> ApiError.BadRequest(
                    formatValidation(validation) ?: "Request failed validation (422).",
                )
                in 500..599 -> ApiError.ServerError(code = null, serverMessage = simple)
                else -> ApiError.Other(simple ?: "Request failed ($status).")
            }
        }

        /** `"Validation failed — loc.path: msg; other.path: msg"`. */
        @PublishedApi
        internal fun formatValidation(items: List<ValidationItem>?): String? {
            if (items.isNullOrEmpty()) return null
            val parts = items.map { item ->
                val loc = (item.loc ?: emptyList()).joinToString(".")
                val msg = item.msg ?: "invalid"
                if (loc.isEmpty()) msg else "$loc: $msg"
            }
            return "Validation failed — ${parts.joinToString("; ")}"
        }

        private fun <T> decodeQuietly(serializer: KSerializer<T>, body: String): T? = try {
            NemaraJson.json.decodeFromString(serializer, body)
        } catch (_: Exception) {
            null
        }
    }
}

/** `{"detail": "some_code_or_message"}` */
@Serializable
@PublishedApi
internal data class SimpleDetail(val detail: String? = null)

/** `{"detail": [{"loc": [...], "msg": "...", "type": "..."}]}` */
@Serializable
@PublishedApi
internal data class ValidationEnvelope(val detail: List<ValidationItem>? = null)

@Serializable
@PublishedApi
internal data class ValidationItem(
    // List<String>, matching iOS's `loc: [String]?` — and inheriting its
    // weakness. pydantic's `loc` is heterogeneous: it mixes field-name strings
    // with integer array indices, e.g. ["body", 0, "name"]. Decoding that as
    // strings throws, the whole envelope is dropped, and the caregiver sees the
    // generic "Request failed validation (422)." instead of the field that was
    // wrong. iOS has exactly this bug. Recorded as HARDENING.md H-8 rather than
    // fixed here, because a port that quietly improves error handling makes the
    // two platforms diverge in a way nobody notices until they disagree.
    val loc: List<String>? = null,
    val msg: String? = null,
    val type: String? = null,
)
