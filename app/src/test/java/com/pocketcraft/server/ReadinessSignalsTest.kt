package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessSignalsTest {
    @Test fun javaReadinessOnlyAfterDoneMessage() {
        val r=ReadinessSignals()
        r.observe("[Server thread/INFO]: Preparing spawn area")
        assertFalse(r.javaReady)
        r.observe("[Server thread/INFO]: Done (5.73s)! For help, type \"help\"")
        assertTrue(r.javaReady)
        assertEquals("JAVA READY",r.state())
    }

    @Test fun externalRegistrationConfirmsNethernetAndListener() {
        val r=ReadinessSignals()
        r.observe("[Geyser-Fabric] Initializing NetherNet signaling")
        assertFalse(r.nethernetSignalingReady)
        r.observe("[Geyser-Fabric] Registered with the external signaling service")
        assertTrue(r.nethernetRegistered)
        assertTrue(r.nethernetSignalingReady)
        assertTrue(r.bedrockListener)
        assertTrue(r.bedrockEvidence.contains("External"))
        r.reset()
        assertFalse(r.nethernetRegistered)
        assertFalse(r.bedrockListener)
    }

    @Test fun bothBuiltInHttpAndHttpsConfirmNethernet() {
        for (url in listOf("http://0.0.0.0:19132", "https://example.test:19133")) {
            val r=ReadinessSignals()
            r.observe("[Geyser-Fabric] Built-in signaling started on $url, with NetherNet on UDP port 19132")
            assertTrue(r.nethernetBuiltIn)
            assertTrue(r.nethernetSignalingReady)
            assertTrue(r.bedrockListener)
            assertFalse(r.nethernetRegistered)
        }
    }

    @Test fun builtInFailureDoesNotClaimReadiness() {
        val r=ReadinessSignals()
        r.observe("[Geyser-Fabric] Built-in signaling could not start. Make sure no other program is using TCP port 19132")
        r.observe("[Geyser-Fabric] Initializing NetherNet signaling")
        assertFalse(r.bedrockListener)
        assertFalse(r.nethernetSignalingReady)
    }

    @Test fun nativeLibraryErrorDoesNotFakeReadiness() {
        val r=ReadinessSignals()
        r.observe("[Server thread/INFO]: Done (1.3s)! For help, type help")
        r.observe("NetherNet will not start! Could not load its WebRTC native library on Linux (aarch64)")
        r.observe("java.lang.UnsatisfiedLinkError: Cannot identify the JVM's libc")
        assertTrue(r.javaReady)
        assertTrue(r.nethernetNativeFailure)
        assertFalse(r.bedrockListener)
        assertEquals("JAVA READY", r.state())
    }

    @Test fun actualSignalCanClearPriorFailure() {
        val r=ReadinessSignals()
        r.observe("NetherNet will not start! Could not load its WebRTC native library")
        assertTrue(r.nethernetNativeFailure)
        r.observe("[Geyser-Fabric] Built-in signaling started on http://0.0.0.0:19132")
        assertFalse(r.nethernetNativeFailure)
        assertTrue(r.bedrockListener)
    }

    @Test fun bothPublishedRaknetMessagesAreRecognized() {
        for (line in listOf(
            "[Geyser-Fabric] Started Geyser on 0.0.0.0:19132",
            "[Geyser-Fabric] Started Geyser on UDP port 19132",
            "[Geyser-Fabric] Started Geyser on 192.168.1.40:25580"
        )) {
            val r=ReadinessSignals()
            r.observe(line)
            assertTrue(r.bedrockListener)
            assertFalse(r.nethernetSignalingReady)
        }
    }

    @Test fun failedGeyserAndJavaJoinDoNotBecomeBedrockReady() {
        val r=ReadinessSignals()
        r.observe("[Geyser-Fabric] Failed to start Geyser on 0.0.0.0:19132")
        r.observe("[Server thread/INFO]: Player connected with username Steve")
        assertFalse(r.bedrockListener)
    }

    @Test fun successfulGeyserBedrockJoinIsProofEvenWithoutPortLog() {
        val r=ReadinessSignals()
        r.observe("[Geyser-Fabric] Player connected with username Alex")
        assertTrue(r.bedrockListener)
        assertTrue(r.bedrockPlayerJoined)
        assertEquals("Geyser Bedrock join reported", r.bedrockEvidence)
    }

    @Test fun manualEvidenceBelongsOnlyToCurrentRun() {
        val r=ReadinessSignals()
        r.confirmBedrockJoin()
        assertTrue(r.bedrockListener)
        assertTrue(r.userConfirmedBedrock)
        r.reset()
        assertFalse(r.bedrockListener)
        assertFalse(r.userConfirmedBedrock)
    }

    @Test fun geyserListenerIsIndependentFromJavaDone() {
        val r=ReadinessSignals()
        r.observe("[Geyser-Fabric] Started Geyser on 0.0.0.0:19132")
        assertEquals("STARTING",r.state())
        r.observe("[Server thread/INFO]: Done (11.8s)! For help, type \"help\"")
        assertEquals("JAVA + BEDROCK READY",r.state())
    }
}
