package com.yugahashimoto.andcode.runtime.remote

import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.RuntimeState
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteTargetVersionProbeTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `selects v2 backend for 2x servers`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18","pid":7}"""))
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18","pid":7}"""))

            val target = target()
            val result = target.connect()

            assertTrue(result.isSuccess)
            assertEquals("2.0.18", result.getOrThrow().version)
            assertTrue(target.state.value is RuntimeState.Connected)
            assertFalse(target.capabilities.editMessages)
            assertEquals("/api/info", server.takeRequest().path)
            assertEquals("/api/info", server.takeRequest().path)
        }

    @Test
    fun `keeps v1 backend when v2 probe misses`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.18.5"}"""))

            val target = target()
            val result = target.connect()

            assertTrue(result.isSuccess)
            assertEquals("1.18.5", result.getOrThrow().version)
            assertTrue(target.capabilities.editMessages)
            assertEquals("/api/info", server.takeRequest().path)
            assertEquals("/global/health", server.takeRequest().path)
        }

    private fun target(): RemoteRuntimeTarget =
        RemoteRuntimeTarget(
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
