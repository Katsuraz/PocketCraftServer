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
        assertEquals("Bedrock player joined (Geyser/Floodgate)", r.bedrockEvidence)
    }


    @Test fun floodgatePlayerLoginProvesBedrockEvenWhenGeyserDoesNotLogStartup() {
        val r = ReadinessSignals()
        r.observe("[15:11:03] [Server thread/INFO]: Done (5.1s)! For help, type \"help\"")
        r.observe("[15:11:10] [Server thread/INFO]: .Alex[/192.168.1.5:49800] logged in with entity id 44 at (1.0, 65.0, 1.0)")
        assertTrue(r.bedrockPlayerJoined)
        assertTrue(r.bedrockListener)
        assertEquals("JAVA + BEDROCK READY", r.state())
        assertFalse(r.nethernetSignalingReady)
    }

    @Test fun floodgateCustomPrefixIsRecognized() {
        val r = ReadinessSignals()
        r.setFloodgateUsernamePrefix("*")
        r.observe("[Server thread/INFO]: *Player_01[/127.0.0.1:12345] logged in with entity id 7 at (0, 64, 0)")
        assertTrue(r.bedrockPlayerJoined)
        r.reset()
        assertFalse(r.bedrockListener)
    }

    @Test fun disabledFloodgatePrefixDoesNotGuessPlayerType() {
        val r = ReadinessSignals()
        r.setFloodgateUsernamePrefix("")
        r.observe("[Server thread/INFO]: .Alex[/192.168.1.5:49800] logged in with entity id 44 at (1, 2, 3)")
        assertFalse(r.bedrockListener)
    }

    @Test fun ordinaryJavaLoginsOrChatCannotFakeBedrockJoin() {
        val r = ReadinessSignals()
        for (line in listOf(
            "[Server thread/INFO]: Steve[/192.168.1.5:49800] logged in with entity id 44 at (1, 2, 3)",
            "[Server thread/INFO]: <Steve> .Alex[/127.0.0.1:4567] logged in with entity id 7",
            "[Server thread/INFO]: .Alex joined the game",
            "[Server thread/INFO]: .Alex lost connection: Disconnected",
            "[Server thread/INFO]: .Alex[/127.0.0.1:4567] FAILED logged in with entity id 7",
            "[Server thread/INFO]: .Alex[/127.0.0.1:4567] logged in with entity id BAD"
        )) r.observe(line)
        assertFalse(r.bedrockListener)
    }

    @Test fun geyserAlternateStartedMessageIsRecognized() {
        val r = ReadinessSignals()
        r.observe("[Geyser-Fabric] Geyser started on UDP port 19132")
        assertTrue(r.bedrockListener)
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
