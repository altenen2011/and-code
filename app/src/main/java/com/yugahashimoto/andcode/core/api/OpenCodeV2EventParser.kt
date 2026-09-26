package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Parses OpenCode v2 (`GET /api/event`) frames.
 *
 * Each SSE `data:` line carries a frame object `{id, created?, type, location?, data, durable?}`
 * where `data` is usually an inline object (occasionally a JSON-encoded string). The event
 * vocabulary below was captured against a live 2.0.18 server (see `docs/OPENCODE_V2.md`):
 * `server.connected`, `session.inbox.*`, `session.execution.started`, `session.step.*`,
 * `session.text.*`, `session.tool.*`, `session.retry.scheduled`, `session.usage.updated`,
 * `shell.*`, `project.updated`, plus best-effort permission/form fallbacks.
 *
 * Anything unrecognized becomes [V2Event.Unknown], never a crash.
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
     * @param frameEvent the SSE `event:` field, used only when the frame itself carries no `type`
     * (v2 frames normally do).
     * @param frameData the SSE `data:` payload: the frame object, or (from relays that strip the
     * envelope) the inner event object itself.
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
            (frame["type"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: (inner["type"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: (frame["event"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: frameEvent?.takeIf { it.isNotBlank() }
                ?: return V2Event.Unknown("missing-type", frameData)
        val data = (inner["properties"] as? JsonObject) ?: innerPayloadData(frame, inner)

        return runCatching {
            when (type) {
                "server.connected" -> V2Event.ServerConnected
                "session.created" -> V2Event.SessionCreated(decodeSession(data))
                "session.updated" -> V2Event.SessionUpdated(decodeSession(data))
                "session.deleted" ->
                    V2Event.SessionDeleted(
                        string(data, "sessionID") ?: string(data, "id"),
                    )
                "message.updated" -> {
                    val message = v2MessageFromJson(messageSource(data))
                    V2Event.MessageUpserted(
                        sessionId = message.sessionId ?: string(data, "sessionID"),
                        message = message,
                    )
                }
                "session.text.delta" ->
                    V2Event.MessageDelta(
                        sessionId = string(data, "sessionID"),
                        messageId = string(data, "assistantMessageID"),
                        partId = "text-${string(data, "ordinal") ?: "0"}",
                        delta = string(data, "delta"),
                    )
                "session.retry.scheduled" -> {
                    val error = data["error"] as? JsonObject
                    V2Event.RunFailed(
                        sessionId = string(data, "sessionID"),
                        message = error?.let(::describeError),
                        name = (error?.get("type") as? JsonPrimitive)?.content,
                    )
                }
                "session.step.ended" -> {
                    val finish = string(data, "finish")
                    val sessionId = string(data, "sessionID") ?: return V2Event.Unknown(type, frameData)
                    if (finish == null || finish == "stop" || finish == "aborted" || finish == "cancelled") {
                        V2Event.StatusChanged(sessionId, "idle")
                    } else {
                        V2Event.RunFailed(sessionId, finish, "StepError")
                    }
                }
                "session.inbox.enqueued",
                "session.inbox.delivered",
                "session.execution.started",
                "session.step.started",
                "session.step.streamed",
                "session.text.started",
                "session.text.ended",
                "session.tool.called",
                "session.tool.input.started",
                "session.tool.input.ended",
                "session.tool.progress",
                "session.tool.success",
                "session.usage.updated",
                "shell.created",
                "shell.exited",
                -> {
                    // shell.* events carry no session id of their own; silently dropping them
                    // would stall the spinner, so only session-less frames stay unknown.
                    val sessionId = sessionIdOf(data) ?: return V2Event.Unknown(type, frameData)
                    V2Event.StatusChanged(sessionId, "busy")
                }
                "permission.asked", "permission.requested" ->
                    V2Event.PermissionAsked(decodePermission(data))
                "form.created", "form.updated", "question.asked" ->
                    V2Event.FormAsked(
                        sessionId = string(data, "sessionID"),
                        formId = string(data, "id"),
                        summary = string(data, "title") ?: string(data, "message"),
                    )
                else -> fallback(type, data, frameData)
            }
        }.getOrElse { V2Event.Unknown(type, frameData) }
    }

    /**
     * Last-resort mapping for event names this client has not seen yet: permission-like events
     * become approval requests, form/question-like events become questions, everything else stays
     * unknown. Keeps future server additions functional instead of silent.
     */
    private fun fallback(
        type: String,
        data: JsonObject,
        frameData: String,
    ): V2Event {
        val lower = type.lowercase()
        return when {
            "permission" in lower && ("ask" in lower || "request" in lower) ->
                V2Event.PermissionAsked(decodePermission(data))
            "form" in lower || "question" in lower ->
                V2Event.FormAsked(
                    sessionId = string(data, "sessionID") ?: string(data, "sessionId"),
                    formId = string(data, "id") ?: string(data, "formID"),
                    summary = string(data, "title") ?: string(data, "message") ?: string(data, "question"),
                )
            else -> V2Event.Unknown(type, frameData)
        }
    }

    /**
     * Unwraps the frame: `data` is usually an inline object, occasionally a JSON-encoded string
     * (the documented `V2EventEncoded` shape), and some relays pre-decode it.
     */
    private fun innerPayload(frame: JsonObject): JsonObject? {
        val data = frame["data"] ?: return null
        if (data is JsonObject) return data
        if (data is JsonPrimitive && data.isString) {
            return runCatching { json.parseToJsonElement(data.content).jsonObject }.getOrNull()
        }
        return null
    }

    /** The object the event fields live in: `properties` (legacy envelope), `data`, or the frame. */
    private fun innerPayloadData(
        frame: JsonObject,
        inner: JsonObject,
    ): JsonObject {
        val data = frame["data"]
        if (data is JsonObject) return data
        return inner
    }

    private fun sessionIdOf(data: JsonObject): String? =
        string(data, "sessionID")
            ?: ((data["info"] as? JsonObject)?.let { string(it, "sessionID") })
            ?: ((data["metadata"] as? JsonObject)?.let { string(it, "sessionID") })

    private fun messageSource(data: JsonObject): JsonObject =
        (data["message"] as? JsonObject)
            ?: (data["info"] as? JsonObject)
            ?: data

    private fun string(
        obj: JsonObject,
        key: String,
    ): String? = (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun decodeSession(data: JsonObject): V2Session {
        val source =
            (data["info"] as? JsonObject)
                ?: (data["session"] as? JsonObject)
                ?: data
        return json.decodeFromJsonElement(V2Session.serializer(), source)
    }

    private fun decodePermission(data: JsonObject): V2PermissionRequest {
        val source = (data["request"] as? JsonObject) ?: data
        return json.decodeFromJsonElement(V2PermissionRequest.serializer(), source)
    }

    /**
     * Human-readable error text from v2 error objects (`{"type": ..., "message": ...}`) or the
     * v1-style (`{"name": ..., "data": {"message": ...}}`) shape.
     */
    private fun describeError(element: JsonObject): String? {
        val message =
            (element["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: ((element["data"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content?.takeIf {
                    it.isNotBlank()
                }
        val name =
            (element["type"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: (element["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return when {
            message != null && name != null -> "$name: $message"
            message != null -> message
            name != null -> name
            else -> element.toString()
        }
    }
}
