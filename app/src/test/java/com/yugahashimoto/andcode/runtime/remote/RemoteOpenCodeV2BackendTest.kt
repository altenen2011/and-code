package com.yugahashimoto.andcode.runtime.remote

import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteOpenCodeV2BackendTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: RemoteOpenCodeV2Backend

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        backend =
            RemoteOpenCodeV2Backend(
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
    fun `health maps server info`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18","pid":7}"""))

            val health = backend.health()

            assertTrue(health.healthy)
            assertEquals("2.0.18", health.version)
            assertEquals("/api/info", server.takeRequest().path)
        }

    @Test
    fun `list sessions hides archived entries`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"id":"ses_1","title":"A","time":{"created":1,"updated":2}},{"id":"ses_2","title":"B","time":{"created":1,"updated":2,"archived":3}}],"cursor":{}}""",
                ),
            )

            val sessions = backend.listSessions()

            assertEquals(listOf("ses_1"), sessions.map { it.id })
        }

    @Test
    fun `send switches differing agent and model before prompting`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"ses_1","agent":"build","model":{"id":"m","providerID":"p"},"time":{"created":1,"updated":2}}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{}"""))
            server.enqueue(MockResponse().setBody("""{}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"id":"msg_1","sessionID":"ses_1"}}"""))

            backend.sendMessage(
                "ses_1",
                PromptRequest(text = "Hello", providerId = "p", modelId = "m2", agent = "plan"),
            )

            val requests = List(4) { server.takeRequest() }
            assertEquals(
                listOf(
                    "/api/session/ses_1",
                    "/api/session/ses_1/agent",
                    "/api/session/ses_1/model",
                    "/api/session/ses_1/prompt",
                ),
                requests.map { it.path },
            )
            assertTrue(requests[1].body.readUtf8().contains("plan"))
            assertTrue(requests[2].body.readUtf8().contains("m2"))
            assertTrue(requests[3].body.readUtf8().contains("Hello"))
        }

    @Test
    fun `send skips switches when session already matches`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"ses_1","agent":"plan","model":{"id":"m2","providerID":"p"},"time":{"created":1,"updated":2}}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"data":{"id":"msg_1","sessionID":"ses_1"}}"""))

            backend.sendMessage(
                "ses_1",
                PromptRequest(text = "Hello", providerId = "p", modelId = "m2", agent = "plan"),
            )

            assertEquals("/api/session/ses_1", server.takeRequest().path)
            val prompt = server.takeRequest()
            assertEquals("/api/session/ses_1/prompt", prompt.path)
            assertTrue(prompt.body.readUtf8().contains("Hello"))
        }

    @Test
    fun `send forwards attachments as file mentions`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"ses_1","time":{"created":1,"updated":2}}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"data":{"id":"msg_1","sessionID":"ses_1"}}"""))

            backend.sendMessage(
                "ses_1",
                PromptRequest(
                    text = "Look",
                    attachments =
                        listOf(
                            PromptAttachment(
                                filename = "a.png",
                                mime = "image/png",
                                url = "file:///tmp/a.png",
                            ),
                        ),
                ),
            )

            server.takeRequest()
            val prompt = server.takeRequest()
            val body = prompt.body.readUtf8()
            assertTrue(body.contains("file:///tmp/a.png"))
            assertTrue(body.contains("a.png"))
        }

    @Test
    fun `permission answers map remember to always`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{}"""))

            val ok = backend.respondToPermission("ses_1", "per_1", PermissionResponse.ONCE, remember = true)

            assertTrue(ok)
            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/permission/per_1/reply", request.path)
            assertTrue(request.body.readUtf8().contains("always"))
        }

    @Test
    fun `messages map roles from kinds`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"id":"msg_1","sessionID":"ses_1","type":"user","text":"hi"},{"id":"msg_2","sessionID":"ses_1","type":"assistant","text":"yo"}],"cursor":{}}""",
                ),
            )

            val messages = backend.listMessages("ses_1")

            assertEquals(listOf("user", "assistant"), messages.map { it.info.role })
            assertEquals("hi", messages[0].text)
        }

    @Test
    fun `projects files and vcs map from location routes`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""[{"id":"prj_1","canonical":"repo","name":"Repo"}]"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"location":{},"data":[{"path":"src/Main.kt","type":"file"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"location":{},"data":{"provider":"git","branch":{"current":"main","default":"main"}}}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"location":{},"data":[{"file":"a.kt","additions":3,"deletions":1,"status":"modified"}]}""",
                ),
            )

            val projects = backend.listProjects()
            val files = backend.listFiles("/ws", ".")
            val vcs = backend.vcsInfo("/ws")
            val status = backend.vcsStatus("/ws")

            assertEquals("prj_1", projects[0].id)
            assertEquals("/api/project", server.takeRequest().path)
            assertEquals("Main.kt", files[0].name)
            assertEquals("/api/fs/list", server.takeRequest().path)
            assertEquals("main", vcs.branch)
            assertEquals("/api/vcs", server.takeRequest().path)
            assertEquals("a.kt", status[0].file)
            assertEquals("/api/vcs/status", server.takeRequest().path)
        }

    @Test
    fun `answers resolve the form then reply with first field key`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"location":{},"data":[{"id":"frm_1","sessionID":"ses_1","title":"Pick one","fields":[{"key":"choice","type":"string"}]}]}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{}"""))

            val ok = backend.answerQuestion("frm_1", listOf(listOf("yes")), null)

            assertTrue(ok)
            assertEquals("/api/form", server.takeRequest().path)
            val reply = server.takeRequest()
            assertEquals("/api/session/ses_1/form/frm_1/reply", reply.path)
            assertTrue(reply.body.readUtf8().contains("choice"))
            assertTrue(reply.body.readUtf8().contains("yes"))
        }

    @Test
    fun `rejecting a vanished form succeeds without a call`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"location":{},"data":[]}"""))

            val ok = backend.rejectQuestion("frm_gone", null)

            assertTrue(ok)
            assertEquals("/api/form", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
}
