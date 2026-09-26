package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal OpenCode v2 (2.x) server API models.
 *
 * V2 is an intentional server-API break (see `docs/OPENCODE_V2.md`): every route lives under
 * `/api/*`, list/create calls wrap payloads in `{data: ...}` (lists add `{location, data}`), and
 * session IDs use the `ses_*`, message IDs the `msg_*`, permission IDs the `per_*` shape.
 *
 * Everything here decodes leniently (unknown keys ignored, generous defaults) because the app
 * talks to whatever 2.x build is installed on-device or remote, and the schema drifts between
 * minor releases. Field shapes were taken from the released v2 OpenAPI document and MUST be
 * re-verified against a live server before the v1 backend is switched over.
 */
@Serializable
data class V2ServerInfo(
    val version: String = "",
    val pid: Long = 0L,
)

@Serializable
data class V2SessionTime(
    val created: Double = 0.0,
    val updated: Double = 0.0,
    val archived: Double? = null,
)

@Serializable
data class V2ModelRef(
    val id: String = "",
    @SerialName("providerID") val providerId: String = "",
    val variant: String? = null,
) {
    /** `provider/model#variant`, the canonical v2 model reference used by prompt and switch calls. */
    val canonical: String
        get() = buildString {
            if (providerId.isNotBlank()) append(providerId).append('/')
            append(id)
            if (!variant.isNullOrBlank()) append('#').append(variant)
        }
}

@Serializable
data class V2Session(
    val id: String = "",
    val title: String = "",
    val agent: String? = null,
    val model: V2ModelRef? = null,
    @SerialName("projectID") val projectId: String? = null,
    val time: V2SessionTime = V2SessionTime(),
) {
    val isArchived: Boolean
        get() = time.archived != null
}

@Serializable
data class V2PermissionRequest(
    val id: String = "",
    @SerialName("sessionID") val sessionId: String = "",
    val action: String = "",
    val resources: List<String> = emptyList(),
    val message: String? = null,
)

@Serializable
data class V2Provider(
    val id: String = "",
    val name: String = id,
)

@Serializable
data class V2Agent(
    val id: String = "",
    val name: String = id,
    val description: String? = null,
    val mode: String? = null,
)

@Serializable
data class V2Model(
    val id: String = "",
    @SerialName("providerID") val providerId: String? = null,
    @SerialName("modelID") val modelId: String? = null,
    val name: String? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: modelId ?: id
}

/** The `{data: ...}` inbox entry returned by `POST /api/session/{id}/prompt`. */
@Serializable
data class V2InboxEntry(
    val id: String = "",
    @SerialName("sessionID") val sessionId: String = "",
)

/**
 * A v2 session message. The wire type is a union of per-kind objects, so the client keeps the
 * raw payload and exposes best-effort [id], [kind], and display [text]. Renderers must tolerate
 * missing text (tool/synthetic/system entries often carry none).
 */
data class V2Message(
    val id: String,
    val sessionId: String?,
    val kind: String,
    val text: String,
    val raw: JsonObject,
)

internal fun v2MessageFromJson(element: JsonObject): V2Message {
    val info = element["info"] as? JsonObject
    val source = info ?: element
    return V2Message(
        id =
            (source["id"] as? JsonPrimitive)?.content
                ?: (element["id"] as? JsonPrimitive)?.content.orEmpty(),
        sessionId =
            (source["sessionID"] as? JsonPrimitive)?.content
                ?: (element["sessionID"] as? JsonPrimitive)?.content,
        kind =
            (source["type"] as? JsonPrimitive)?.content
                ?: (element["type"] as? JsonPrimitive)?.content
                ?: "unknown",
        text = collectV2Text(source),
        raw = element,
    )
}

/** Collects every string stored under a `text` key, depth-first. Good enough for chat display. */
private fun collectV2Text(element: JsonElement): String {
    val out = StringBuilder()
    fun visit(node: JsonElement) {
        when (node) {
            is JsonObject ->
                node.entries.forEach { (key, value) ->
                    if (key == "text" && value is JsonPrimitive && value.isString) {
                        out.append(value.content)
                    } else {
                        visit(value)
                    }
                }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        }
    }
    visit(element)
    return out.toString()
}

sealed interface V2Event {
    data object ServerConnected : V2Event

    data class SessionCreated(val session: V2Session) : V2Event

    data class SessionUpdated(val session: V2Session) : V2Event

    data class SessionDeleted(val sessionId: String?) : V2Event

    data class MessageDelta(val sessionId: String?, val delta: String?) : V2Event

    data class PermissionAsked(val request: V2PermissionRequest) : V2Event

    data class FormAsked(val sessionId: String?, val summary: String?) : V2Event

    data class Unknown(val type: String, val raw: String) : V2Event
}

fun V2Event.sessionIdOrNull(): String? =
    when (this) {
        is V2Event.SessionCreated -> session.id
        is V2Event.SessionUpdated -> session.id
        is V2Event.SessionDeleted -> sessionId
        is V2Event.MessageDelta -> sessionId
        is V2Event.PermissionAsked -> request.sessionId
        is V2Event.FormAsked -> sessionId
        is V2Event.ServerConnected -> null
        is V2Event.Unknown -> null
    }
