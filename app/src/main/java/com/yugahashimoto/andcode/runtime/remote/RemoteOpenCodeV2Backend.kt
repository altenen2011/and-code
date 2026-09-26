package com.yugahashimoto.andcode.runtime.remote

import com.yugahashimoto.andcode.core.api.ConfiguredProvider
import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodePathInfo
import com.yugahashimoto.andcode.core.api.OpenCodeProject
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import com.yugahashimoto.andcode.core.api.OpenCodeV2ApiClient
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderAuthAuthorization
import com.yugahashimoto.andcode.core.api.ProviderAuthMethod
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.core.api.V2FileAttachment
import com.yugahashimoto.andcode.core.api.V2ModelRef
import com.yugahashimoto.andcode.core.api.integrationMethodsToAuthMethods
import com.yugahashimoto.andcode.core.api.toAgent
import com.yugahashimoto.andcode.core.api.toCommand
import com.yugahashimoto.andcode.core.api.toConfiguredProvider
import com.yugahashimoto.andcode.core.api.toEvent
import com.yugahashimoto.andcode.core.api.toFileChange
import com.yugahashimoto.andcode.core.api.toFileNode
import com.yugahashimoto.andcode.core.api.toHealth
import com.yugahashimoto.andcode.core.api.toMcpServer
import com.yugahashimoto.andcode.core.api.toMessage
import com.yugahashimoto.andcode.core.api.toPathInfo
import com.yugahashimoto.andcode.core.api.toProject
import com.yugahashimoto.andcode.core.api.toProviderCatalog
import com.yugahashimoto.andcode.core.api.toQuestion
import com.yugahashimoto.andcode.core.api.toSession
import com.yugahashimoto.andcode.core.api.toSkill
import com.yugahashimoto.andcode.core.api.toVcsInfo
import com.yugahashimoto.andcode.core.api.v2FileContent
import com.yugahashimoto.andcode.core.api.v2VcsMode
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    /** OAuth attempt ids per provider, bridging begin (dialog step 1) and complete (step 2). */
    private val oauthAttempts = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun health(): OpenCodeHealth = client.info().toHealth()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        client.sessions(directory).map { it.toSession() }.filter { it.time.archived == null }

    override suspend fun session(sessionId: String): OpenCodeSession = client.session(sessionId).toSession()

    override suspend fun activeSessionIds(): Set<String> = client.activeSessionIds()

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = client.createSession(title = title).toSession()

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> =
        // The v2 listing carries no order guarantee; the transcript must read chronologically or
        // user bubbles land after the replies that followed them.
        client.messages(sessionId).map { it.toMessage() }.sortedWith(
            compareBy({ it.info.time.created }, { it.info.id }),
        )

    override suspend fun listProviders(): ProviderCatalog = toProviderCatalog(client.providers(), client.models(), client.integrations())

    override suspend fun listAgents(): List<OpenCodeAgent> = client.agents().map { it.toAgent() }

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> =
        client.integrations().associate { integration ->
            integration.id to integrationMethodsToAuthMethods(integration.methods)
        }

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization {
        val detail = client.integration(providerId)
        val raw = detail.methods.getOrNull(methodIndex) ?: error("sign-in method is not available")
        require((raw["type"] as? JsonPrimitive)?.content == "oauth") {
            "sign-in method is not available"
        }
        val methodId =
            (raw["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: error("sign-in method is not available")
        val answer =
            inputs.entries
                .filter { it.value.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.let { entries ->
                    buildJsonObject { entries.forEach { (key, value) -> put(key, value) } }
                }
        val attempt = client.beginOAuth(providerId, methodId, answer)
        oauthAttempts[providerId] = attempt.attemptId
        return ProviderAuthAuthorization(
            url = attempt.url,
            method = if (attempt.mode == "auto") "auto" else "code",
            instructions = attempt.instructions.orEmpty(),
        )
    }

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = client.connectWithKey(providerId, apiKey, metadata["label"])

    override suspend fun removeProviderAuth(providerId: String): Boolean {
        val credentialIds =
            client.integration(providerId).connections
                .filter { it.isCredential }
                .mapNotNull { it.id }
        credentialIds.forEach { client.removeCredential(it) }
        return true
    }

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean {
        if (code != null) {
            val attemptId =
                oauthAttempts[providerId]
                    ?: error("sign-in expired; start again")
            return client.completeOAuth(providerId, attemptId, code)
        }
        // Auto mode finishes in the browser; poll for the credential appearing.
        return client.integration(providerId).connections.any { it.isCredential }
    }

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> = client.projects().map { it.toProject() }

    override suspend fun currentProject(directory: String?): OpenCodeProject {
        val project =
            client.locationInfo(directory).project
                ?: error("OpenCode v2 server reported no project")
        return project.toProject()
    }

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = client.locationInfo(directory).toPathInfo()

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = client.fsEntries(directory, path).map { it.toFileNode(directory) }

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = v2FileContent(path, client.fsRead(directory, path))

    /** V2 has no file-status route; VCS status is the same git data the v1 route returned. */
    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = vcsStatus(directory)

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = client.fsFind(directory, query, limit).map { it.path }

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = client.vcsInfo(directory).toVcsInfo()

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = client.vcsStatus(directory).map { it.toFileChange() }

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = client.vcsDiff(directory, v2VcsMode(mode), context).map { it.toFileChange() }

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = client.sessionDiff(sessionId).map { it.toFileChange() }

    override suspend fun mcpServers(): List<McpServer> = client.mcpServers().map { it.toMcpServer() }

    override suspend fun config(): JsonElement = client.configRaw()

    override suspend fun configProviders(): List<ConfiguredProvider> = client.integrations().map { it.toConfiguredProvider() }

    override suspend fun commands(): List<OpenCodeCommand> = client.commands().map { it.toCommand() }

    override suspend fun skills(): List<OpenCodeSkill> = client.skills().map { it.toSkill() }

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = client.pendingForms().map { it.toQuestion() }

    /**
     * Answers a v2 form by replying `{answer: {<first field key>: <first answer>}}`. The form is
     * resolved through the pending list to recover the session id the v1 signature drops.
     */
    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean {
        val form =
            client.pendingForms().firstOrNull { it.id == requestId }
                ?: error("OpenCode v2 form not found: $requestId")
        val key =
            form.fields.firstOrNull()?.get("key") as? JsonPrimitive
                ?: error("OpenCode v2 form has no answerable field: $requestId")
        val value = answers.firstOrNull()?.firstOrNull().orEmpty()
        return client.replyForm(
            sessionId = form.sessionId,
            formId = form.id,
            answer = buildJsonObject { put(key.content, value) },
        )
    }

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean {
        val form = client.pendingForms().firstOrNull { it.id == requestId } ?: return true
        return client.cancelForm(form.sessionId, form.id)
    }

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
