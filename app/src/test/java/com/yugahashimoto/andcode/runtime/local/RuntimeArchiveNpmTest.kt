package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class RuntimeArchiveNpmTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `npm integrity round trip accepts matching sha512`() {
        val file = temp.newFile("payload.bin")
        file.writeBytes("opencode-v2-payload".toByteArray())
        val digest = MessageDigest.getInstance("SHA-512").digest(file.readBytes())
        val integrity = "sha512-" + java.util.Base64.getEncoder().encodeToString(digest)

        RuntimeArchive.verifyNpmIntegrity(file, integrity)
    }

    @Test
    fun `npm integrity rejects mismatched digest`() {
        val file = temp.newFile("payload.bin")
        file.writeBytes("opencode-v2-payload".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeArchive.verifyNpmIntegrity(file, "sha512-" + "A".repeat(86) + "==")
        }
    }

    @Test
    fun `npm integrity rejects other algorithms`() {
        val file = temp.newFile("payload.bin")
        file.writeBytes("x".toByteArray())

        val error =
            assertThrows(IllegalStateException::class.java) {
                RuntimeArchive.verifyNpmIntegrity(file, "sha256-abc")
            }
        assertTrue(error.message!!.contains("sha512"))
    }

    @Test
    fun `server password is a 32 char alphanumeric secret`() {
        val first = generateServerPassword()
        val second = generateServerPassword()

        assertEquals(32, first.length)
        assertTrue(first.all { it.isLetterOrDigit() })
        assertTrue(first != second)
    }
}
