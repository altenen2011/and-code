package com.yugahashimoto.andcode.runtime.local

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBundleManifestTest {
    private val architecture =
        LocalRuntimeArchitecture(
            alpineUrl = "https://example.com/alpine.tar.gz",
            alpineSha256 = "a".repeat(64),
            openCodeUrl = "https://example.com/opencode.tar.gz",
            openCodeSha256 = "b".repeat(64),
            npmTarballUrl = "https://registry.npmjs.org/pkg.tgz",
            npmIntegrity = "sha512-" + "A".repeat(86) + "==",
        )
    private val manifest =
        LocalRuntimeManifest(
            schemaVersion = 1,
            runtimeVersion = "2026.09.26.1",
            openCodeVersion = "2.0.18",
            openCodeChannel = LocalRuntimeManifest.CHANNEL_NPM,
            alpineVersion = "3.24.1",
            port = 4097,
            architectures = mapOf("arm64-v8a" to architecture),
        )

    private fun bundle(extra: Map<String, String> = emptyMap()): JSONObject {
        val base =
            mapOf(
                "schemaVersion" to 1,
                "alpineVersion" to "3.24.1",
                "alpineSha256" to "a".repeat(64),
                "openCodeVersion" to "2.0.18",
                "openCodeChannel" to "npm",
                "openCodeIntegrity" to "sha512-" + "A".repeat(86) + "==",
            ) + extra
        val json = JSONObject()
        base.forEach { (key, value) -> json.put(key, value) }
        return json
    }

    @Test
    fun `matching bundle verifies`() {
        verifyPortableBundleManifest(bundle(), manifest, architecture, "arm64-v8a")
    }

    @Test
    fun `stale opencode version fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyPortableBundleManifest(
                bundle(mapOf("openCodeVersion" to "2.0.17")),
                manifest,
                architecture,
                "arm64-v8a",
            )
        }
    }

    @Test
    fun `wrong integrity fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyPortableBundleManifest(
                bundle(mapOf("openCodeIntegrity" to "sha512-" + "B".repeat(86) + "==")),
                manifest,
                architecture,
                "arm64-v8a",
            )
        }
    }

    @Test
    fun `missing manifest marker fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyPortableBundleManifest(
                JSONObject(),
                manifest,
                architecture,
                "arm64-v8a",
            )
        }
    }
}
