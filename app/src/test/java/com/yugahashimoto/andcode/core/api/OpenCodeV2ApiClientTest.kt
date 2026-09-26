package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCodeV2ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenCodeV2ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            OpenCodeV2ApiClient(
                ConnectionProfile(
                    id = "mac",
                    name = "Mac mini",
                    baseUrl = server.url("/").toString(),
                    username = "opencode",
                    password = "pw",
                    allowInsecureLan = true,
                ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reads server info from api info`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18","pid":4242,"urls":[],"paths":{"tmp":"/tmp"}}"""))

            val info = client.info()

            assertEquals("2.0.18", info.version)
            assertEquals("/api/info", server.takeRequest().path)
        }

    @Test
    fun `lists sessions from data envelope`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"id":"ses_abc","title":"Mobile","agent":"build","time":{"created":1,"updated":2}}],"cursor":{}}""",
                ),
            )

            val sessions = client.sessions()

            assertEquals(1, sessions.size)
            assertEquals("ses_abc", sessions[0].id)
            assertEquals("Mobile", sessions[0].title)
            assertEquals("/api/session", server.takeRequest().path)
        }

    @Test
    fun `queues prompt and returns inbox entry`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"data":{"id":"msg_1","sessionID":"ses_abc"}}"""))

            val entry = client.prompt("ses_abc", "Hello")

            assertEquals("msg_1", entry.id)
            assertEquals("ses_abc", entry.sessionId)
            val request = server.takeRequest()
            assertEquals("/api/session/ses_abc/prompt", request.path)
            assertTrue(request.body.readUtf8().contains("Hello"))
        }

    @Test
    fun `replies to permission request with decision`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{}"""))

            val ok = client.replyPermission("ses_abc", "per_1", "once")

            assertTrue(ok)
            val request = server.takeRequest()
            assertEquals("/api/session/ses_abc/permission/per_1/reply", request.path)
            assertTrue(request.body.readUtf8().contains("once"))
        }

    @Test
    fun `replies to form with answer object`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{}"""))

            val ok =
                client.replyForm(
                    "ses_abc",
                    "frm_1",
                    buildJsonObject { put("answer", buildJsonObject { put("choice", "yes") }) },
                )

            assertTrue(ok)
            val request = server.takeRequest()
            assertEquals("/api/session/ses_abc/form/frm_1/reply", request.path)
            assertTrue(request.body.readUtf8().contains("choice"))
        }

    @Test
    fun `lists shells and reads output`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"location":{},"data":[{"id":"sh_1","status":"running","command":"sleep 60"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"location":{},"data":{"output":"partial...","cursor":9,"size":9,"truncated":true}}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(204))

            val shells = client.shells()
            val output = client.shellOutput("sh_1")
            val removed = client.removeShell("sh_1")

            assertEquals("sh_1", shells.single().id)
            assertEquals("running", shells.single().status)
            assertEquals("/api/shell", server.takePath())
            assertTrue(output.truncated)
            assertEquals("/api/shell/sh_1/output", server.takePath())
            assertTrue(removed)
            assertEquals("/api/shell/sh_1", server.takePath())
        }

    @Test
    fun `backgrounds a session without a body`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(204))

            assertTrue(client.background("ses_abc"))
            assertEquals("/api/session/ses_abc/background", server.takeRequest().path)
        }

    private fun MockWebServer.takePath(): String = takeRequest().path?.substringBefore("?").orEmpty()
}

class OpenCodeV2EventParserTest {
    private val parser = OpenCodeV2EventParser()

    @Test
    fun `maps legacy session updated from encoded string payload`() {
        val inner = """{"type":"session.updated","properties":{"info":{"id":"ses_abc","title":"Mobile"}}}"""
        val frame = """{"id":"1","event":"session.updated","data":${jsonString(inner)}}"""

        val event = parser.parse("session.updated", frame)

        assertTrue(event is V2Event.SessionUpdated)
        assertEquals("ses_abc", (event as V2Event.SessionUpdated).session.id)
    }

    @Test
    fun `parses permission asked`() {
        val inner =
            """{"type":"permission.asked","properties":{"request":{"id":"per_1","sessionID":"ses_abc","action":"shell","resources":["ls"]}}}"""
        val frame = """{"id":"2","event":"permission.asked","data":${jsonString(inner)}}"""

        val event = parser.parse("permission.asked", frame)

        assertTrue(event is V2Event.PermissionAsked)
        val request = (event as V2Event.PermissionAsked).request
        assertEquals("per_1", request.id)
        assertEquals("shell", request.action)
    }

    @Test
    fun `maps unknown types to unknown without throwing`() {
        val event = parser.parse("something.new", """{"id":"3","event":"something.new","data":"{}"}""")

        assertTrue(event is V2Event.Unknown)
    }

    @Test
    fun `maps invalid payloads to unknown without throwing`() {
        val event = parser.parse(null, "not-json{")

        assertTrue(event is V2Event.Unknown)
    }

    @Test
    fun `parses live server connected frame`() {
        val event = parser.parse(null, """{"id":"evt_1","type":"server.connected","data":{}}""")

        assertTrue(event is V2Event.ServerConnected)
    }

    @Test
    fun `parses live text delta frame`() {
        val frame =
            """{"id":"evt_2","created":1790441281571,"type":"session.text.delta","data":{"sessionID":"ses_1","assistantMessageID":"msg_9","ordinal":0,"delta":"Hi!"}}"""

        val event = parser.parse(null, frame)

        assertTrue(event is V2Event.MessageDelta)
        val delta = event as V2Event.MessageDelta
        assertEquals("ses_1", delta.sessionId)
        assertEquals("msg_9", delta.messageId)
        assertEquals("text-0", delta.partId)
        assertEquals("Hi!", delta.delta)
    }

    @Test
    fun `parses live step ended frame as idle`() {
        val frame =
            """{"id":"evt_3","type":"session.step.ended","data":{"sessionID":"ses_1","assistantMessageID":"msg_9","finish":"stop"}}"""

        val event = parser.parse(null, frame)

        assertEquals(V2Event.StatusChanged("ses_1", "idle"), event)
    }

    @Test
    fun `parses live retry scheduled frame as failure with provider message`() {
        val frame =
            """{"id":"evt_4","type":"session.retry.scheduled","data":{"sessionID":"ses_1","assistantMessageID":"msg_9","attempt":2,"error":{"type":"provider.internal","message":"No channel","status":503}}}"""

        val event = parser.parse(null, frame)

        assertTrue(event is V2Event.RunFailed)
        val failed = event as V2Event.RunFailed
        assertEquals("ses_1", failed.sessionId)
        assertTrue(failed.message!!.contains("No channel"))
        assertEquals("provider.internal", failed.name)
    }

    @Test
    fun `sessionless shell frames stay unknown`() {
        val event =
            parser.parse(
                null,
                """{"id":"evt_5","type":"shell.exited","data":{"id":"sh_1","exit":0,"status":"exited"}}""",
            )

        assertTrue(event is V2Event.Unknown)
    }

    @Test
    fun `tool called frame keeps the session busy`() {
        val event =
            parser.parse(
                null,
                """{"id":"evt_6","type":"session.tool.called","data":{"sessionID":"ses_1","assistantMessageID":"msg_9","id":"call_1"}}""",
            )

        assertEquals(V2Event.StatusChanged("ses_1", "busy"), event)
    }

    private fun jsonString(raw: String): String = '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
}
