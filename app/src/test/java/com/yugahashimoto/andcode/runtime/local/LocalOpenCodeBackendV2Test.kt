package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.remote.RemoteOpenCodeBackend
import com.yugahashimoto.andcode.runtime.remote.RemoteOpenCodeV2Backend
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOpenCodeBackendV2Test {
    @Test
    fun `builds v1 backend by default`() {
        val backend = LocalOpenCodeBackend(portProvider = { 4097 })

        assertTrue(backend.delegate() is RemoteOpenCodeBackend)
    }

    @Test
    fun `builds v2 backend when runtime reports 2x`() {
        val backend =
            LocalOpenCodeBackend(
                portProvider = { 4097 },
                useV2 = { true },
            )

        assertTrue(backend.delegate() is RemoteOpenCodeV2Backend)
    }
}
