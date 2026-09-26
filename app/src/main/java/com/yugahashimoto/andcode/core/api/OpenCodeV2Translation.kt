package com.yugahashimoto.andcode.core.api

/**
 * Translates OpenCode v2 models/events into the v1 shapes the rest of the app (chat, pickers,
 * approvals) is built against.
 *
 * This keeps Phase 2 UI-agnostic: backends can serve a v2 server while every ViewModel keeps
 * working unchanged. Each mapping documents what v2 cannot express; those gaps close in later
 * phases once live-server behavior is verified (see `docs/OPENCODE_V2.md`).
 */
fun V2ServerInfo.toHealth(): OpenCodeHealth = OpenCodeHealth(healthy = version.isNotBlank(), version = version)

/** V2 timestamps are epoch seconds (fractional); v1 uses epoch milliseconds. */
fun v2EpochSecondsToMillis(seconds: Double): Long = (seconds * 1000.0).toLong()

fun V2Session.toSession(): OpenCodeSession =
    OpenCodeSession(
        id = id,
        title = title,
        projectId = projectId,
        time =
            OpenCodeTime(
                created = v2EpochSecondsToMillis(time.created),
                updated = v2EpochSecondsToMillis(time.updated),
                archived = time.archived?.let(::v2EpochSecondsToMillis),
            ),
    )

fun V2ModelRef.toModelReference(): OpenCodeModelReference = OpenCodeModelReference(providerID = providerId, modelID = id)

/**
 * Best-effort message mapping. `user`/`assistant` kinds keep their role; every other v2 entry
 * (system, shell, skill, compaction, …) renders as an assistant entry carrying whatever `text`
 * the payload has. Tool-call structure is not reconstructed here — the timeline shows the text,
 * and rich part rendering arrives with live-server verification.
 */
fun V2Message.toMessage(): OpenCodeMessage {
    val role = if (kind == "user") "user" else "assistant"
    val messageId = id.ifBlank { "v2-$kind-${raw.hashCode()}" }
    val info =
        OpenCodeMessageInfo(
            id = messageId,
            sessionId = sessionId.orEmpty(),
            role = role,
        )
    val parts =
        if (text.isNotBlank()) {
            listOf(
                OpenCodePart(
                    type = "text",
                    text = text,
                    messageID = messageId,
                    sessionId = sessionId,
                ),
            )
        } else {
            emptyList()
        }
    return OpenCodeMessage(info = info, parts = parts)
}

fun V2PermissionRequest.toPermissionRequest(): PermissionRequest =
    PermissionRequest(
        id = id,
        sessionId = sessionId,
        permission = action,
        patterns = resources,
    )

fun V2Agent.toAgent(): OpenCodeAgent =
    OpenCodeAgent(
        // The identifier round-trips to `POST /api/session/{id}/agent`; the display name does not.
        name = id,
        description = description,
        mode = mode,
    )

/** Joins the v2 provider and model listings into the catalog pickers expect. */
fun toProviderCatalog(
    providers: List<V2Provider>,
    models: List<V2Model>,
): ProviderCatalog {
    val modelsByProvider = models.groupBy { it.providerId ?: "unknown" }
    return ProviderCatalog(
        all =
            providers.map { provider ->
                OpenCodeProvider(
                    id = provider.id,
                    name = provider.name,
                    models =
                        modelsByProvider[provider.id].orEmpty().associate { model ->
                            model.id to
                                OpenCodeModel(
                                    id = model.id,
                                    providerID = model.providerId,
                                    name = model.displayName,
                                )
                        },
                )
            },
        // V2 exposes connection state through the integration/credential routes (Phase 2b);
        // pickers treat every listed provider as available until then.
        default = emptyMap(),
        connected = emptyList(),
    )
}

fun V2Event.toEvent(): OpenCodeEvent =
    when (this) {
        is V2Event.ServerConnected -> OpenCodeEvent.ServerConnected
        is V2Event.SessionCreated -> OpenCodeEvent.SessionCreated(session.toSession())
        is V2Event.SessionUpdated -> OpenCodeEvent.SessionUpdated(session.toSession())
        is V2Event.SessionDeleted -> OpenCodeEvent.Unknown("session.deleted", sessionId.orEmpty())
        is V2Event.MessageUpserted -> OpenCodeEvent.MessageUpdated(message.toMessage().info)
        is V2Event.MessageDelta ->
            OpenCodeEvent.MessagePartDelta(
                sessionId = sessionId.orEmpty(),
                messageId = messageId.orEmpty(),
                partId = partId.orEmpty(),
                field = "text",
                delta = delta.orEmpty(),
            )
        is V2Event.PermissionAsked -> OpenCodeEvent.PermissionAsked(request.toPermissionRequest())
        is V2Event.FormAsked ->
            OpenCodeEvent.QuestionAsked(
                QuestionRequest(
                    // Provisional id: real `frm_*` answering lands with the forms API (Phase 2b).
                    id = formId ?: "v2form-${sessionId.orEmpty()}-${summary.orEmpty().hashCode()}",
                    sessionId = sessionId.orEmpty(),
                    questions = listOf(QuestionPrompt(question = summary.orEmpty())),
                ),
            )
        is V2Event.Unknown -> OpenCodeEvent.Unknown(type, raw)
    }
