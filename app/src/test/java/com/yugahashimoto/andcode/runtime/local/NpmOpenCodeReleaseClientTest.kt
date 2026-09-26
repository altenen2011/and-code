package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NpmOpenCodeReleaseClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NpmOpenCodeReleaseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NpmOpenCodeReleaseClient(registry = server.url("/").toString().toHttpUrl())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `resolves latest version from registry`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18"}"""))

            assertEquals("2.0.18", client.latestVersion())
            assertEquals("/@opencode%2Fcli/latest", server.takeRequest().path)
        }

    @Test
    fun `reports update available with platform tarball and integrity`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"version":"2.0.18","dist":{"tarball":"https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.18.tgz","integrity":"sha512-${"A".repeat(86)}=="}}""",
                ),
            )

            val check = client.check("2.0.17", "arm64-v8a")
            assertTrue(check is NpmUpdateCheck.Available)
            val release = (check as NpmUpdateCheck.Available).release
            assertEquals("2.0.18", release.version)
            assertEquals("@opencode/cli-linux-arm64-musl", release.packageName)
            assertTrue(release.tarballUrl.startsWith("https://"))
            assertEquals("sha512", release.algorithm)

            val paths = listOf(server.takeRequest().path, server.takeRequest().path)
            assertTrue(paths[1]!!.contains("cli-linux-arm64-musl"))
        }

    @Test
    fun `reports up to date when current matches latest`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18"}"""))

            val check = client.check("2.0.18", "arm64-v8a")
            assertTrue(check is NpmUpdateCheck.UpToDate)
        }

    @Test
    fun `rejects platform metadata without integrity hash`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"version":"2.0.18","dist":{"tarball":"https://registry.npmjs.org/pkg.tgz","integrity":""}}""",
                ),
            )

            var failed = false
            try {
                client.platformRelease("2.0.18", "arm64-v8a")
            } catch (error: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
        }

    @Test
    fun `rejects unsupported abi`() =
        runBlocking {
            var failed = false
            try {
                client.platformRelease("2.0.18", "armeabi-v7a")
            } catch (error: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
        }
}
