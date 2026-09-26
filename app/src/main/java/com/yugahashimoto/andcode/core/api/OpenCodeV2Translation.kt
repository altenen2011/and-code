package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Translates OpenCode v2 models/events into the v1 shapes the rest of the app (chat, pickers,
 * approvals) is built against.
 *
 * This keeps Phase 2 UI-agnostic: backends can serve a v2 server while every ViewModel keeps
 * working unchanged. Each mapping documents what v2 cannot express; those gaps close in later
 * phases once live-server behavior is verified (see `docs/OPENCODE_V2.md`).
 */
fun V2ServerInfo.toHealth(): OpenCodeHealth = OpenCodeHealth(healthy = version.isNotBlank(), version = version)

/** V2 timestamps are epoch milliseconds (verified against a live 2.0.18 server). */
fun v2TimestampToMillis(timestamp: Double): Long = timestamp.toLong()

fun V2Session.toSession(): OpenCodeSession =
    OpenCodeSession(
        id = id,
        title = title,
        projectId = projectId,
        time =
            OpenCodeTime(
                created = v2TimestampToMillis(time.created),
                updated = v2TimestampToMillis(time.updated),
                archived = time.archived?.let(::v2TimestampToMillis),
            ),
    )

fun V2ModelRef.toModelReference(): OpenCodeModelReference = OpenCodeModelReference(providerId = providerId, modelId = id)

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
                    messageId = messageId,
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
                                    providerId = model.providerId,
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
        is V2Event.StatusChanged ->
            OpenCodeEvent.SessionStatusChanged(
                sessionId = sessionId.orEmpty(),
                status = status,
            )
        is V2Event.RunFailed -> OpenCodeEvent.SessionError(sessionId, message, name)
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

/** Phase 2b translations. Location-scoped v2 routes take the workspace directory as `location`. */

fun V2Project.toProject(): OpenCodeProject =
    OpenCodeProject(
        id = id,
        // V2 projects expose no worktree path; the canonical id keeps scoping keys stable.
        worktree = canonical.orEmpty(),
        name = name,
    )

fun V2LocationInfo.toPathInfo(): OpenCodePathInfo =
    OpenCodePathInfo(
        home = "",
        state = "",
        config = "",
        worktree = project?.directory.orEmpty(),
        directory = directory,
    )

fun V2LocationProject.toProject(): OpenCodeProject =
    OpenCodeProject(
        id = id,
        worktree = directory,
        name = canonical,
    )

fun V2FileEntry.toFileNode(): OpenCodeFileNode =
    OpenCodeFileNode(
        name = path.substringAfterLast('/').ifBlank { path },
        path = path,
        absolute = path,
        type = type,
    )

fun v2FileContent(
    path: String,
    text: String,
): OpenCodeFileContent =
    OpenCodeFileContent(
        type = "file",
        content = text,
        encoding = "utf8",
    )

fun V2FileStatus.toFileChange(): OpenCodeFileChange =
    OpenCodeFileChange(
        file = file,
        additions = additions.toDouble(),
        deletions = deletions.toDouble(),
        status = status.takeIf { it.isNotBlank() },
    )

fun V2FileDiff.toFileChange(): OpenCodeFileChange =
    OpenCodeFileChange(
        file = file,
        patch = patch,
        additions = additions.toDouble(),
        deletions = deletions.toDouble(),
        status = status?.takeIf { it.isNotBlank() },
    )

fun V2VcsInfo.toVcsInfo(): OpenCodeVcsInfo =
    OpenCodeVcsInfo(
        branch = branch?.current,
        defaultBranch = branch?.defaultBranch,
    )

fun V2McpServer.toMcpServer(): McpServer =
    McpServer(
        name = name,
        status = v2McpStatusName(status),
    )

private fun v2McpStatusName(status: JsonElement?): String? =
    when (status) {
        is JsonObject -> (status["status"] as? JsonPrimitive)?.content
        is JsonPrimitive -> status.content.takeIf { status.isString }
        else -> null
    }

fun V2Command.toCommand(): OpenCodeCommand = OpenCodeCommand(name = name, description = description)

fun V2Skill.toSkill(): OpenCodeSkill =
    OpenCodeSkill(
        name = name.ifBlank { id },
        description = description,
        location = path,
    )

fun V2Integration.toConfiguredProvider(): ConfiguredProvider =
    ConfiguredProvider(
        id = id,
        name = name,
        connected = connections.isNotEmpty(),
    )

fun V2Form.toQuestion(): QuestionRequest =
    QuestionRequest(
        id = id,
        sessionId = sessionId,
        questions = listOf(QuestionPrompt(question = title)),
    )

/** V1 `git` mode has no v2 counterpart; `working` is the closest review base. */
fun v2VcsMode(mode: String): String = if (mode == "git") "working" else mode
