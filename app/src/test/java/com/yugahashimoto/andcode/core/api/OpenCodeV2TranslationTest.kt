package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeV2TranslationTest {
    @Test
    fun `keeps millisecond timestamps as is`() {
        assertEquals(1790440217782L, v2TimestampToMillis(1790440217782.0))
    }

    @Test
    fun `maps session with archived marker`() {
        val session =
            V2Session(
                id = "ses_1",
                title = "Mobile",
                time = V2SessionTime(created = 1790440217782.0, updated = 1790440217783.0, archived = 1790440217784.0),
            ).toSession()

        assertEquals("ses_1", session.id)
        assertEquals("Mobile", session.title)
        assertEquals(1790440217782L, session.time.created)
        assertEquals(1790440217784L, session.time.archived)
    }

    @Test
    fun `maps session token usage for the context bar`() {
        val session =
            V2Session(
                id = "ses_1",
                tokens = V2SessionTokens(input = 100L, output = 20L, cache = V2SessionCacheTokens(read = 30L)),
            ).toSession()

        assertEquals(130L, session.tokens?.contextUsed)
    }

    @Test
    fun `maps user and assistant messages and folds other kinds`() {
        val user = v2MessageFromJson(messageJson("msg_1", "user", "hi")).toMessage()
        assertEquals("user", user.info.role)
        assertEquals("hi", user.text)
        assertEquals("msg_1-p0", user.parts.single().id)

        val system = v2MessageFromJson(messageJson("msg_2", "system", "note")).toMessage()
        assertEquals("assistant", system.info.role)
        assertEquals("note", system.text)

        val empty = v2MessageFromJson(messageJson("msg_3", "shell", null)).toMessage()
        assertTrue(empty.parts.isEmpty())
    }

    @Test
    fun `joins providers and models into catalog`() {
        val catalog =
            toProviderCatalog(
                providers = listOf(V2Provider(id = "anthropic", name = "Anthropic")),
                models =
                    listOf(
                        V2Model(id = "anthropic/claude", providerId = "anthropic", modelId = "claude"),
                        V2Model(id = "orphan", providerId = null),
                    ),
            )

        assertEquals(1, catalog.all.size)
        assertEquals(1, catalog.all[0].models.size)
        assertEquals("claude", catalog.all[0].models["anthropic/claude"]?.name)
    }

    @Test
    fun `maps permission request action to permission`() {
        val request =
            V2PermissionRequest(id = "per_1", sessionId = "ses_1", action = "shell", resources = listOf("ls"))
                .toPermissionRequest()

        assertEquals("shell", request.permission)
        assertEquals(listOf("ls"), request.patterns)
    }

    @Test
    fun `maps agent id to agent name`() {
        val agent = V2Agent(id = "build", name = "Build", mode = "primary").toAgent()

        assertEquals("build", agent.name)
        assertEquals("primary", agent.mode)
    }

    @Test
    fun `maps events to v1 shapes`() {
        val created =
            V2Event.SessionCreated(V2Session(id = "ses_1", title = "T")).toEvent()
        assertTrue(created is OpenCodeEvent.SessionCreated)

        val delta = V2Event.MessageDelta("ses_1", "msg_1", "prt_1", "stream").toEvent()
        assertTrue(delta is OpenCodeEvent.MessagePartDelta)
        assertEquals("stream", (delta as OpenCodeEvent.MessagePartDelta).delta)

        val asked =
            V2Event.PermissionAsked(V2PermissionRequest(id = "per_1", sessionId = "ses_1", action = "edit"))
                .toEvent()
        assertTrue(asked is OpenCodeEvent.PermissionAsked)

        val unknown = V2Event.Unknown("nope", "{}").toEvent()
        assertTrue(unknown is OpenCodeEvent.Unknown)
    }

    @Test
    fun `server info maps blank version to unhealthy`() {
        assertTrue(V2ServerInfo(version = "2.0.18").toHealth().healthy)
        assertTrue(!V2ServerInfo().toHealth().healthy)
    }

    @Test
    fun `maps workspace browsing models`() {
        val node = V2FileEntry(path = "src/Main.kt", type = "file").toFileNode("/ws")
        assertEquals("Main.kt", node.name)
        assertEquals("src/Main.kt", node.path)
        assertEquals("/ws/src/Main.kt", node.absolute)

        val dir = V2FileEntry(path = "src/", type = "directory").toFileNode("/ws")
        assertEquals("src", dir.name)

        val content = v2FileContent("a.kt", "hello")
        assertEquals("hello", content.content)

        val change =
            V2FileDiff(file = "a.kt", patch = "@@\n", additions = 2L, deletions = 1L, status = "modified")
                .toFileChange()
        assertEquals("@@\n", change.patch)
        assertEquals("modified", change.status)

        val vcs = V2VcsInfo(provider = "git", branch = V2VcsBranch(current = "main")).toVcsInfo()
        assertEquals("main", vcs.branch)

        val project = V2Project(id = "prj_1", canonical = "repo", name = "Repo").toProject()
        assertEquals("repo", project.worktree)
    }

    @Test
    fun `maps mcp status objects to names`() {
        val connected =
            V2McpServer(
                name = "playwright",
                status = buildJsonObject { put("status", "connected") },
            ).toMcpServer()
        assertEquals("connected", connected.status)

        val bare = V2McpServer(name = "x").toMcpServer()
        assertEquals(null, bare.status)
    }

    @Test
    fun `maps commands skills and forms`() {
        assertEquals("review", V2Command(name = "review").toCommand().name)
        assertEquals("s", V2Skill(id = "s", name = "", path = "p").toSkill().name)

        val question = V2Form(id = "frm_1", sessionId = "ses_1", title = "Pick?").toQuestion()
        assertEquals("frm_1", question.id)
        assertEquals("ses_1", question.sessionId)
        assertEquals("Pick?", question.questions[0].question)
    }

    @Test
    fun `maps status and failure events`() {
        val idle = V2Event.StatusChanged("ses_1", "idle").toEvent()
        assertTrue(idle is OpenCodeEvent.SessionStatusChanged)
        assertEquals("idle", (idle as OpenCodeEvent.SessionStatusChanged).status)

        val failed = V2Event.RunFailed("ses_1", "provider.internal: No channel", "provider.internal").toEvent()
        assertTrue(failed is OpenCodeEvent.SessionError)
        assertEquals("provider.internal: No channel", (failed as OpenCodeEvent.SessionError).message)
    }

    @Test
    fun `maps legacy git diff mode to working`() {
        assertEquals("working", v2VcsMode("git"))
        assertEquals("branch", v2VcsMode("branch"))
    }

    private fun messageJson(
        id: String,
        type: String,
        text: String?,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("sessionID", "ses_1")
            put("type", type)
            text?.let { put("text", it) }
        }
}
