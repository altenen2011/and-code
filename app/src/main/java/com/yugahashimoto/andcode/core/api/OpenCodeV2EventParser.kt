package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses OpenCode v2 (`GET /api/event`) frames.
 *
 * Each SSE `data:` line carries a frame object `{id, event, data}` where `data` is a JSON-encoded
 * string (`V2EventEncoded`, media type `application/json`). The inner payload uses the familiar
 * `{type, properties}` envelope. Exact v2 event names are provisional — they were inferred from
 * the v1 vocabulary and the v2 schema names, and MUST be confirmed against a live 2.x server
 * (see `docs/OPENCODE_V2.md`). Anything unrecognized becomes [V2Event.Unknown], never a crash.
 */
class OpenCodeV2EventParser(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    /**
     * Parses one SSE frame.
     *
     * @param frameEvent the SSE `event:` field, used as the type fallback when the inner payload
     * has no `type`.
     * @param frameData the SSE `data:` payload: either the frame object or, when the transport
     * already stripped the envelope, the inner event object itself.
     */
    fun parse(
        frameEvent: String?,
        frameData: String,
    ): V2Event {
        val frame =
            runCatching { json.parseToJsonElement(frameData).jsonObject }.getOrNull()
                ?: return V2Event.Unknown(frameEvent ?: "invalid", frameData)
        val inner = innerPayload(frame) ?: frame
        val type =
            (inner["type"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: (frame["event"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: frameEvent?.takeIf { it.isNotBlank() }
                ?: return V2Event.Unknown("missing-type", frameData)
        val properties = (inner["properties"] as? JsonObject) ?: inner

        return runCatching {
            when (type) {
                "server.connected" -> V2Event.ServerConnected
                "session.created" -> V2Event.SessionCreated(decodeSession(properties))
                "session.updated" -> V2Event.SessionUpdated(decodeSession(properties))
                "session.deleted" ->
                    V2Event.SessionDeleted(
                        (properties["sessionID"] as? JsonPrimitive)?.content
                            ?: (properties["id"] as? JsonPrimitive)?.content,
                    )
                "message.delta", "message.part.delta", "message.updated", "message.part.updated" ->
                    V2Event.MessageDelta(
                        sessionId = (properties["sessionID"] as? JsonPrimitive)?.content,
                        delta =
                            (properties["delta"] as? JsonPrimitive)?.content
                                ?: (properties["text"] as? JsonPrimitive)?.content,
                    )
                "permission.asked", "permission.updated", "permission.requested" ->
                    V2Event.PermissionAsked(decodePermission(properties))
                "form.created", "form.updated", "question.asked" ->
                    V2Event.FormAsked(
                        sessionId = (properties["sessionID"] as? JsonPrimitive)?.content,
                        summary =
                            (properties["title"] as? JsonPrimitive)?.content
                                ?: (properties["message"] as? JsonPrimitive)?.content,
                    )
                else -> V2Event.Unknown(type, frameData)
            }
        }.getOrElse { V2Event.Unknown(type, frameData) }
    }

    /**
     * Unwraps the frame: `data` is usually a JSON-encoded string, occasionally an inline object
     * (some relays pre-decode it). Returns null when the frame carries no usable payload.
     */
    private fun innerPayload(frame: JsonObject): JsonObject? {
        val data = frame["data"] ?: return null
        if (data is JsonObject) return data
        if (data is JsonPrimitive && data.isString) {
            return runCatching { json.parseToJsonElement(data.content).jsonObject }.getOrNull()
        }
        return null
    }

    private fun decodeSession(properties: JsonObject): V2Session {
        val source =
            (properties["info"] as? JsonObject)
                ?: (properties["session"] as? JsonObject)
                ?: properties
        return json.decodeFromJsonElement(V2Session.serializer(), source)
    }

    private fun decodePermission(properties: JsonObject): V2PermissionRequest {
        val source = (properties["request"] as? JsonObject) ?: properties
        return json.decodeFromJsonElement(V2PermissionRequest.serializer(), source)
    }
}
