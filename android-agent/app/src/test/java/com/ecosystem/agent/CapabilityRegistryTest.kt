package com.ecosystem.agent.capabilities

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demonstrates the mock-implementation pattern for testing: capabilities
 * are plain interfaces with no direct Android framework calls required at
 * the unit-test layer (a fake implementation like this one substitutes for
 * a real CameraCapability/MicrophoneCapability in dispatcher-level tests).
 */
private class FakeCapability(
    override val name: String,
    private val granted: Boolean = true,
) : Capability {
    override val handledCommands: Set<String> = setOf("fake_command")
    override fun isPermissionGranted(): Boolean = granted
    override suspend fun handleCommand(command: String, params: JsonObject, sessionId: String): CapabilityResult =
        CapabilityResult.Success(buildJsonObject {})
}

class CapabilityRegistryTest {

    @Test
    fun `registers capability and resolves command owner`() {
        val registry = CapabilityRegistry()
        val cap = FakeCapability("fake.capability")
        registry.register(cap)

        assertEquals(cap, registry.ownerOf("fake_command"))
        assertNull(registry.ownerOf("unknown_command"))
    }

    @Test
    fun `handshake list reflects permission state`() {
        val registry = CapabilityRegistry()
        registry.register(FakeCapability("fake.granted", granted = true))
        registry.register(FakeCapability("fake.denied", granted = false))

        val list = registry.toHandshakeList()
        assertEquals(2, list.size)
        assertTrue(list.any { it["name"] == "fake.granted" && it["permission_granted"] == true })
        assertTrue(list.any { it["name"] == "fake.denied" && it["permission_granted"] == false })
    }

    @Test
    fun `handle command returns success via fake`() = runBlocking {
        val cap = FakeCapability("fake.capability")
        val result = cap.handleCommand("fake_command", buildJsonObject {}, "session-1")
        assertTrue(result is CapabilityResult.Success)
    }
}
