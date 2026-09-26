package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomProviderStoreV2Test {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `v2 sync writes providers entry with package settings`() {
        val rootfs = temp.newFolder("v2-rootfs")
        val memory = memoryStore()
        memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", listOf("m1")))

        val configFile = memory.store.syncToRuntime(rootfs, useV2Providers = true)

        val provider = Json.parseToJsonElement(configFile.readText()).jsonObject["providers"]!!.jsonObject["acme"]!!.jsonObject
        assertEquals("aisdk:@ai-sdk/openai-compatible", provider["package"]!!.jsonPrimitive.content)
        assertEquals("https://acme.example/v1", provider["settings"]!!.jsonObject["baseURL"]!!.jsonPrimitive.content)
        assertTrue("m1" in provider["models"]!!.jsonObject)
    }

    @Test
    fun `v2 sync scrubs legacy entries this store owns`() {
        val rootfs = temp.newFolder("scrub-rootfs")
        val memory = memoryStore()
        memory.store.upsert(CustomProviderDefinition("acme", "Acme", "https://acme.example/v1", listOf("m1")))
        memory.store.syncToRuntime(rootfs, useV2Providers = false)

        memory.store.syncToRuntime(rootfs, useV2Providers = true)

        val root =
            Json.parseToJsonElement(
                java.io.File(rootfs, "root/.config/opencode/opencode.json").readText(),
            ).jsonObject
        assertFalse("acme" in (root["provider"]?.jsonObject.orEmpty()))
        assertTrue("acme" in root["providers"]!!.jsonObject)
    }

    private fun memoryStore(): MemoryProviderStore {
        val definitions = mutableListOf<CustomProviderDefinition>()
        val syncedIds = mutableSetOf<String>()
        val store =
            CustomProviderStore(
                load = { definitions.toList() },
                save = {
                    definitions.clear()
                    definitions.addAll(it)
                },
                loadSyncedIds = { syncedIds.toSet() },
                saveSyncedIds = {
                    syncedIds.clear()
                    syncedIds.addAll(it)
                },
            )
        return MemoryProviderStore(store, syncedIds)
    }

    private data class MemoryProviderStore(
        val store: CustomProviderStore,
        val syncedIds: MutableSet<String>,
    )
}
