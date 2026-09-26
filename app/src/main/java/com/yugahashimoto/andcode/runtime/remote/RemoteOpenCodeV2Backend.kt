package com.yugahashimoto.andcode.runtime.remote

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeV2ApiClient
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.V2FileAttachment
import com.yugahashimoto.andcode.core.api.V2ModelRef
import com.yugahashimoto.andcode.core.api.toAgent
import com.yugahashimoto.andcode.core.api.toEvent
import com.yugahashimoto.andcode.core.api.toHealth
import com.yugahashimoto.andcode.core.api.toMessage
import com.yugahashimoto.andcode.core.api.toProviderCatalog
import com.yugahashimoto.andcode.core.api.toSession
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [OpenCodeBackend] served by an OpenCode v2 (2.x) server.
 *
 * Anything the v1 interface declares but v2 cannot serve yet (provider auth, MCP, config,
 * file/vcs browsers, forms answering, message delete, archive, summarize) falls through to the
 * interface defaults. Wiring into targets happens in Phase 2b after live-server verification;
 * until then the v1 [RemoteOpenCodeBackend] stays in use and this class is covered by unit tests
 * only (see `docs/OPENCODE_V2.md`).
 */
class RemoteOpenCodeV2Backend(
    private val profile: ConnectionProfile,
    private val client: OpenCodeV2ApiClient = OpenCodeV2ApiClient(profile),
) : OpenCodeBackend {
    override val id: String = profile.id
    override val displayName: String = profile.name
    override val kind: BackendKind = BackendKind.REMOTE

    override suspend fun health(): OpenCodeHealth = client.info().toHealth()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        client.sessions().map { it.toSession() }.filter { it.time.archived == null }

    override suspend fun session(sessionId: String): OpenCodeSession = client.session(sessionId).toSession()

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = client.createSession(title = title).toSession()

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = client.messages(sessionId).map { it.toMessage() }

    override suspend fun listProviders(): ProviderCatalog = toProviderCatalog(client.providers(), client.models())

    override suspend fun listAgents(): List<OpenCodeAgent> = client.agents().map { it.toAgent() }

    /**
     * V2 keeps agent/model on the session, so the selected agent/model is switched first (only
     * when it differs from the session's current one) and the text is queued via the inbox
     * prompt afterwards.
     */
    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        val current = client.session(sessionId)
        request.agent?.takeIf { it.isNotBlank() }?.let { agent ->
            if (current.agent != agent) client.switchAgent(sessionId, agent)
        }
        if (!request.providerId.isNullOrBlank() && !request.modelId.isNullOrBlank()) {
            val wanted =
                V2ModelRef(
                    id = request.modelId,
                    providerId = request.providerId,
                    variant = request.variant?.takeIf { it.isNotBlank() },
                )
            if (current.model?.canonical != wanted.canonical) client.switchModel(sessionId, wanted)
        }
        client.prompt(
            sessionId = sessionId,
            text = request.text,
            files =
                request.attachments.map { attachment ->
                    V2FileAttachment(uri = attachment.url, name = attachment.filename.takeIf { it.isNotBlank() })
                },
        )
    }

    override suspend fun abortSession(sessionId: String): Boolean = client.interrupt(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = client.renameSession(sessionId, title).toSession()

    override suspend fun deleteSession(sessionId: String): Boolean = client.deleteSession(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean {
        val decision = if (remember && response == PermissionResponse.ONCE) "always" else response.apiValue
        return client.replyPermission(sessionId, permissionId, decision)
    }

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
    ) {
        client.executeCommand(
            sessionId = sessionId,
            name = command,
            text = "/$command${arguments.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}",
        )
    }

    override fun events(): Flow<OpenCodeEvent> = client.events().map { it.toEvent() }
}
