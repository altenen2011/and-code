package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeV2TranslationTest {
    @Test
    fun `converts epoch seconds to milliseconds`() {
        assertEquals(1500L, v2EpochSecondsToMillis(1.5))
    }

    @Test
    fun `maps session with archived marker`() {
        val session =
            V2Session(
                id = "ses_1",
                title = "Mobile",
                time = V2SessionTime(created = 1.0, updated = 2.0, archived = 3.0),
            ).toSession()

        assertEquals("ses_1", session.id)
        assertEquals("Mobile", session.title)
        assertEquals(1000L, session.time.created)
        assertEquals(3000L, session.time.archived)
    }

    @Test
    fun `maps user and assistant messages and folds other kinds`() {
        val user = v2MessageFromJson(messageJson("msg_1", "user", "hi")).toMessage()
        assertEquals("user", user.info.role)
        assertEquals("hi", user.text)

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
