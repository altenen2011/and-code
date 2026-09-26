package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NpmLocalRuntimeUpdateEngineTest {
    @get:Rule
    val temp = TemporaryFolder()

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
    fun `check maps npm releases to updater releases with integrity`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"version":"2.0.18","dist":{"tarball":"https://registry.npmjs.org/pkg.tgz","integrity":"sha512-${"A".repeat(
                        86,
                    )}=="}}""",
                ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/88137764")
                    .setBody(""),
            )

            val engine = engine()
            val check = engine.check("2.0.17", "arm64-v8a")

            assertTrue(check is LocalRuntimeUpdateCheck.Available)
            val release = (check as LocalRuntimeUpdateCheck.Available).release
            assertEquals("2.0.18", release.version)
            assertEquals(88137764L, release.asset.sizeBytes)
            assertTrue(release.asset.npmIntegrity!!.startsWith("sha512-"))
        }

    @Test
    fun `check reports up to date without touching size probe`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.18"}"""))

            val engine = engine()
            val check = engine.check("2.0.18", "arm64-v8a")

            assertTrue(check is LocalRuntimeUpdateCheck.UpToDate)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `tarball size falls back to content length`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", "12345")
                    .setBody("x"),
            )

            val client = NpmOpenCodeReleaseClient(registry = server.url("/").toString().toHttpUrl())

            assertEquals(12345L, client.tarballSizeBytes("https://registry.npmjs.org/pkg.tgz"))
        }

    @Test
    fun `v2 binary version strings normalize`() {
        assertEquals("2.0.18", normalizeRuntimeVersion("opencode v2.0.18"))
        assertEquals("1.18.5", normalizeRuntimeVersion("v1.18.5"))
        assertEquals("2.0.18", normalizeRuntimeVersion("2.0.18"))
    }

    private fun engine(): NpmLocalRuntimeUpdateEngine {
        val updater =
            LocalRuntimeUpdater(
                runtimeDirectory = temp.newFolder("runtime"),
                abi = "arm64-v8a",
                downloadAsset = { _, _, _ -> },
                candidateVersionProvider = { "2.0.18" },
                accessCoordinator = LocalRuntimeAccessCoordinator(),
            )
        return NpmLocalRuntimeUpdateEngine(
            releaseClient =
                NpmOpenCodeReleaseClient(
                    httpClient = OkHttpClient(),
                    registry = server.url("/").toString().toHttpUrl(),
                ),
            updater = updater,
        )
    }
}
