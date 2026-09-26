package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves OpenCode v2 (2.x) runtime binaries through the npm registry instead of GitHub
 * Releases.
 *
 * V2 is published as `@opencode/cli` with one optional platform package per target
 * (`@opencode/cli-linux-arm64-musl`, `@opencode/cli-linux-x64-musl`, ...). Each version document
 * carries `dist.tarball` (HTTPS URL) and `dist.integrity` (`sha512-<base64>`), which replaces the
 * v1 GitHub-asset `sha256:` digest model used by [LocalRuntimeReleaseClient]. The registry also
 * answers the "latest 2.x" question (`/@opencode%2Fcli/latest`), so the updater no longer depends
 * on the GitHub Releases API at all.
 *
 * Phase 1 only resolves metadata; downloading and integrity verification land with the v2
 * updater (see `docs/OPENCODE_V2.md`).
 */
data class NpmPlatformRelease(
    val version: String,
    val packageName: String,
    val tarballUrl: String,
    /** Raw registry integrity string, e.g. `sha512-<base64>`. */
    val integrity: String,
) {
    val algorithm: String
        get() = integrity.substringBefore('-')

    val digestBase64: String
        get() = integrity.substringAfter('-')
}

sealed interface NpmUpdateCheck {
    val currentVersion: String

    data class UpToDate(
        override val currentVersion: String,
        val latestVersion: String,
    ) : NpmUpdateCheck

    data class Available(
        override val currentVersion: String,
        val release: NpmPlatformRelease,
    ) : NpmUpdateCheck
}

class NpmOpenCodeReleaseClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val registry: HttpUrl = NPM_REGISTRY.toHttpUrl(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    init {
        require(registry.isHttps || registry.host in LOOPBACK_HOSTS) {
            "npm registry must use HTTPS"
        }
    }

    suspend fun latestVersion(): String =
        withContext(Dispatchers.IO) {
            val doc = getDocument("$SCOPE%2Fcli/latest")
            require(doc.version.isNotBlank()) { "npm latest document has no version" }
            normalizeRuntimeVersion(doc.version)
        }

    suspend fun platformRelease(
        version: String,
        abi: String,
    ): NpmPlatformRelease {
        val packageName =
            requireNotNull(PLATFORM_PACKAGE_BY_ABI[abi]) {
                "Unsupported Android ABI for OpenCode v2 updates: $abi"
            }
        val normalized = normalizeRuntimeVersion(version)
        val doc = getDocument("${packageName.replace("/", "%2F")}/$normalized")
        val tarball = doc.dist?.tarball.orEmpty()
        require(tarball.startsWith("https://")) { "npm tarball URL must use HTTPS" }
        val integrity = doc.dist?.integrity.orEmpty()
        require(INTEGRITY.matches(integrity)) {
            "npm package $packageName@$normalized is missing a valid integrity hash"
        }
        return NpmPlatformRelease(
            version = normalizeRuntimeVersion(doc.version.ifBlank { normalized }),
            packageName = packageName,
            tarballUrl = tarball,
            integrity = integrity,
        )
    }

    suspend fun check(
        currentVersion: String,
        abi: String,
    ): NpmUpdateCheck {
        val normalizedCurrent = normalizeRuntimeVersion(currentVersion)
        val latest = latestVersion()
        if (compareRuntimeVersions(latest, normalizedCurrent) <= 0) {
            return NpmUpdateCheck.UpToDate(normalizedCurrent, latest)
        }
        return NpmUpdateCheck.Available(normalizedCurrent, platformRelease(latest, abi))
    }

    private fun getDocument(path: String): NpmVersionDocument {
        val url =
            registry.newBuilder()
                ?.addEncodedPathSegments(path)
                ?.build()
                ?: error("Invalid npm registry path: $path")
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        return httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "npm registry request failed with HTTP ${response.code}"
            }
            val body =
                requireNotNull(response.body) {
                    "npm registry response had no body"
                }
            json.decodeFromString<NpmVersionDocument>(body.string())
        }
    }

    @Serializable
    private data class NpmVersionDocument(
        @SerialName("version") val version: String = "",
        @SerialName("dist") val dist: NpmDist? = null,
    )

    @Serializable
    private data class NpmDist(
        @SerialName("tarball") val tarball: String = "",
        @SerialName("integrity") val integrity: String = "",
    )

    companion object {
        const val NPM_REGISTRY = "https://registry.npmjs.org"
        const val SCOPE = "@opencode"
        private const val USER_AGENT = "AndCode"
        private val INTEGRITY = Regex("^sha512-[A-Za-z0-9+/]+={0,2}$")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        private val PLATFORM_PACKAGE_BY_ABI =
            mapOf(
                "arm64-v8a" to "@opencode/cli-linux-arm64-musl",
                "x86_64" to "@opencode/cli-linux-x64-musl",
            )
    }
}
