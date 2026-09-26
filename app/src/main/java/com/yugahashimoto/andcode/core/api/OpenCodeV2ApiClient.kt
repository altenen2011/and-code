package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.core.security.OpenCodeUrl
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenCode v2 (2.x) HTTP client. Every route lives under `/api/` and most responses wrap their
 * payload in `{data: ...}` (listings add a `location` sibling).
 *
 * This client is additive: the v1 [OpenCodeApiClient] stays untouched until the backends are
 * ported over (see `docs/OPENCODE_V2.md`). Auth reuses the same connection profile (Basic auth),
 * matching v2's `serve`/`pair` credentials.
 */
class OpenCodeV2ApiClient(
    private val profile: ConnectionProfile,
    private val httpClient: OkHttpClient = OpenCodeApiClient.defaultHttpClient(profile),
    private val json: Json = OpenCodeApiClient.defaultJson,
    private val eventParser: OpenCodeV2EventParser = OpenCodeV2EventParser(),
) {
    private val baseUrl: HttpUrl by lazy { OpenCodeUrl.normalize(profile.baseUrl).getOrThrow() }

    suspend fun info(): V2ServerInfo = get("api/info")

    suspend fun sessions(): List<V2Session> = getDataList("api/session")

    suspend fun session(sessionId: String): V2Session = getDataOrDirect("api/session/${encodePath(sessionId)}")

    suspend fun createSession(
        title: String? = null,
        agent: String? = null,
        model: String? = null,
    ): V2Session {
        val body =
            buildJsonObject {
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                agent?.takeIf { it.isNotBlank() }?.let { put("agent", it) }
                model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            }
        return postDataOrDirect("api/session", body)
    }

    suspend fun deleteSession(sessionId: String): Boolean =
        deleteUnit("api/session/${encodePath(sessionId)}")

    suspend fun renameSession(
        sessionId: String,
        title: String,
    ): V2Session {
        val body = buildJsonObject { put("title", title) }
        return patchDataOrDirect("api/session/${encodePath(sessionId)}", body)
    }

    suspend fun messages(sessionId: String): List<V2Message> =
        withContext(Dispatchers.IO) {
            execute(requestBuilder("api/session/${encodePath(sessionId)}/message").get().build()) { body ->
                val root = json.parseToJsonElement(body).jsonObject
                val data = requireNotNull(root["data"]) { "v2 messages response is missing data" }
                json.decodeFromJsonElement<List<JsonObject>>(data).map(::v2MessageFromJson)
            }
        }

    /**
     * Queues a user message in the session inbox and returns the inbox entry. Unlike v1's
     * fire-and-forget `prompt_async`, the turn is tracked through the event stream and the
     * session inbox (`GET /api/session/{id}/inbox`).
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        agent: String? = null,
        model: String? = null,
    ): V2InboxEntry {
        val body =
            buildJsonObject {
                put("text", text)
                agent?.takeIf { it.isNotBlank() }?.let { put("agent", it) }
                model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            }
        return postDataOrDirect("api/session/${encodePath(sessionId)}/prompt", body)
    }

    suspend fun interrupt(sessionId: String): Boolean =
        postUnit("api/session/${encodePath(sessionId)}/interrupt", JsonObject(emptyMap()))

    suspend fun permissionRequests(sessionId: String): List<V2PermissionRequest> =
        getDataList("api/session/${encodePath(sessionId)}/permission")

    /**
     * Replies to a pending permission request. [decision] is `once`, `always`, or `reject`,
     * matching [com.yugahashimoto.andcode.runtime.PermissionResponse.apiValue].
     */
    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: String,
    ): Boolean {
        val body = buildJsonObject { put("decision", decision) }
        return postUnit("api/session/${encodePath(sessionId)}/permission/${encodePath(requestId)}/reply", body)
    }

    suspend fun providers(): List<V2Provider> = getDataList("api/provider")

    suspend fun agents(): List<V2Agent> = getDataList("api/agent")

    suspend fun models(): List<V2Model> = getDataList("api/model")

    fun events(): Flow<V2Event> =
        flow { emitAll(singleEventStream()) }.retryWhen { cause, attempt ->
            val retryable = cause !is OpenCodeApiException || cause.statusCode >= 500
            if (!retryable) return@retryWhen false

            val backoffMillis =
                (500L * (1L shl attempt.toInt().coerceAtMost(5)))
                    .coerceAtMost(15_000L)
            delay(backoffMillis)
            true
        }

    private fun singleEventStream(): Flow<V2Event> =
        channelFlow {
            val eventClient =
                httpClient.newBuilder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            val request =
                requestBuilder("api/event")
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
            val call = eventClient.newCall(request)
            val readerJob =
                launch(Dispatchers.IO) {
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                throw OpenCodeApiException(
                                    statusCode = response.code,
                                    message = "OpenCode v2 event stream failed (HTTP ${response.code})",
                                )
                            }
                            val body = requireNotNull(response.body) { "OpenCode v2 event stream had no body" }
                            body.source().use { source ->
                                var frameEvent: String? = null
                                val data = StringBuilder()
                                while (isActive) {
                                    val line = source.readUtf8Line() ?: break
                                    when {
                                        line.isEmpty() -> {
                                            if (data.isNotEmpty()) {
                                                send(eventParser.parse(frameEvent, data.toString()))
                                                data.setLength(0)
                                                frameEvent = null
                                            }
                                        }
                                        line.startsWith("event:") -> {
                                            frameEvent = line.removePrefix("event:").trim().takeIf { it.isNotEmpty() }
                                        }
                                        line.startsWith("data:") -> {
                                            if (data.isNotEmpty()) data.append('\n')
                                            data.append(line.removePrefix("data:").removePrefix(" "))
                                        }
                                    }
                                }
                                if (data.isNotEmpty()) send(eventParser.parse(frameEvent, data.toString()))
                            }
                            throw IOException("OpenCode v2 event stream closed")
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        close(error)
                    }
                }
            awaitClose {
                call.cancel()
                readerJob.cancel()
            }
        }.buffer(EVENT_BUFFER_CAPACITY)

    private suspend inline fun <reified T> get(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            execute(requestBuilder(path, queryParameters).get().build()) { body -> json.decodeFromString<T>(body) }
        }

    /** Decodes a `{data: [...]}` (or `{location, data: [...]}`) listing. */
    private suspend inline fun <reified T> getDataList(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): List<T> =
        withContext(Dispatchers.IO) {
            execute(requestBuilder(path, queryParameters).get().build()) { body ->
                val root = json.parseToJsonElement(body).jsonObject
                val data = requireNotNull(root["data"]) { "v2 response is missing data ($path)" }
                json.decodeFromJsonElement<List<T>>(data)
            }
        }

    /**
     * Decodes `{data: T}`, falling back to a bare `T` body. Several v2 routes differ between
     * enveloped and direct payloads across minor releases; accepting both keeps the app working
     * without a version gate.
     */
    private suspend inline fun <reified T> getDataOrDirect(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            execute(requestBuilder(path, queryParameters).get().build()) { body -> decodeDataOrDirect<T>(body, path) }
        }

    private suspend inline fun <reified T> postDataOrDirect(
        path: String,
        body: JsonObject,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder(path, queryParameters)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            execute(request) { responseBody -> decodeDataOrDirect<T>(responseBody, path) }
        }

    private suspend inline fun <reified T> patchDataOrDirect(
        path: String,
        body: JsonObject,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder(path, queryParameters)
                    .patch(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            execute(request) { responseBody -> decodeDataOrDirect<T>(responseBody, path) }
        }

    private suspend fun postUnit(
        path: String,
        body: JsonObject,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder(path, queryParameters)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            execute(request) { true }
        }

    private suspend fun deleteUnit(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(path, queryParameters).delete().build()
            execute(request) { true }
        }

    private inline fun <reified T> decodeDataOrDirect(
        body: String,
        path: String,
    ): T {
        val element = json.parseToJsonElement(body)
        val payload =
            if (element is JsonObject && element.containsKey("data")) {
                requireNotNull(element["data"]) { "v2 response is missing data ($path)" }
            } else {
                element
            }
        return json.decodeFromJsonElement<T>(payload)
    }

    private fun <T> execute(
        request: Request,
        parse: (String) -> T,
    ): T {
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OpenCodeApiException(
                    statusCode = response.code,
                    message =
                        formatHttpError(
                            statusCode = response.code,
                            body = bodyText,
                            sensitive = request.isSensitiveRequest(),
                        ),
                )
            }
            return parse(bodyText)
        }
    }

    private fun formatHttpError(
        statusCode: Int,
        body: String,
        sensitive: Boolean = false,
    ): String {
        if (sensitive || statusCode == 401 || statusCode == 403) {
            return "OpenCode v2 request failed (HTTP $statusCode)"
        }
        val snippet =
            body
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .joinToString(" ")
                .take(MAX_ERROR_BODY_CHARS)
        return if (snippet.isBlank()) {
            "OpenCode v2 request failed (HTTP $statusCode)"
        } else {
            "OpenCode v2 request failed (HTTP $statusCode): $snippet"
        }
    }

    private fun requestBuilder(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): Request.Builder {
        val resolved =
            baseUrl.resolve(path.removePrefix("/"))
                ?: throw IllegalArgumentException("Invalid OpenCode v2 API path")
        val url =
            resolved.newBuilder().apply {
                queryParameters.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                profile.password?.takeIf { it.isNotBlank() }?.let { password ->
                    header("Authorization", Credentials.basic(profile.username.ifBlank { "opencode" }, password))
                }
            }
    }

    private fun Request.isSensitiveRequest(): Boolean {
        val path = url.encodedPath
        return path.contains("/credential") ||
            path.contains("/integration") ||
            path.contains("/oauth/")
    }

    private fun encodePath(value: String): String = value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_BODY_CHARS = 240
        private const val EVENT_BUFFER_CAPACITY = 512
    }
}
