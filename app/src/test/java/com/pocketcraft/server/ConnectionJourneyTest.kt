package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionJourneyTest {
    private fun ready() = ConnectionJourney.State(
        javaReady = true,
        serverRunning = true,
        geyserInstalled = true,
        bedrockListener = true,
        nethernetNativeFailure = false,
        nethernetSignalingReady = true,
        lanIp = "192.168.1.50",
        javaPort = "25565",
        playitRunning = true,
        playitAccountConnected = true,
        playitClaimAvailable = true,
        javaPublicAddress = "example.playit.gg:12345",
        bedrockPublicAddress = "example.playit.gg:19132",
        bedrockTransport = "nethernet"
    )

    @Test fun javaLanNeedsServerFirst() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.JAVA_LAN, ready().copy(javaReady=false, serverRunning=false))
        assertEquals(ConnectionJourney.Action.START_SERVER, result.action)
        assertNull(result.address)
    }

    @Test fun javaLanReportsOnlyLocalAddress() {
        val result = ConnectionJourney.assess(ConnectionJourney.Target.JAVA_LAN, ready())
        assertEquals(ConnectionJourney.Action.COPY_ADDRESS, result.action)
        assertEquals("192.168.1.50:25565", result.address)
    }

    @Test fun localAddressRequiresNetwork() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.JAVA_LAN, ready().copy(lanIp = null))
        assertEquals(ConnectionJourney.Action.OPEN_NETWORK_SETTINGS, result.action)
        assertNull(result.address)
    }

    @Test fun bedrockRequiresGeyser() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.BEDROCK_LAN, ready().copy(geyserInstalled=false))
        assertEquals(ConnectionJourney.Action.OPEN_MODS, result.action)
        assertNull(result.address)
    }

    @Test fun nativeFailureNeverSharesBedrockAddress() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.BEDROCK_ONLINE, ready().copy(nethernetNativeFailure=true, bedrockListener=false))
        assertEquals(ConnectionJourney.Action.OPEN_CONSOLE, result.action)
        assertNull(result.address)
        assertTrue(result.title.contains("WebRTC"))
    }

    @Test fun unconfirmedBedrockListenerStillAllowsHonestLanTest() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.BEDROCK_LAN, ready().copy(bedrockListener=false))
        assertEquals(ConnectionJourney.Action.COPY_ADDRESS, result.action)
        assertEquals("192.168.1.50:19132",result.address)
        assertTrue(result.title.contains("not confirmed"))
    }

    @Test fun workingRaknetNotBlockedByAnUnrelatedWebRtcError() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.BEDROCK_LAN,
            ready().copy(nethernetNativeFailure=true, bedrockTransport="raknet"))
        assertEquals(ConnectionJourney.Action.COPY_ADDRESS,result.action)
        assertEquals("192.168.1.50:19132",result.address)
    }

    @Test fun bedrockLanUsesConfiguredPort() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.BEDROCK_LAN, ready().copy(bedrockPort="25432"))
        assertEquals("192.168.1.50:25432", result.address)
    }

    @Test fun raknetDoesNotRequireNethernetSignaling() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.BEDROCK_ONLINE,
            ready().copy(bedrockTransport="raknet", nethernetSignalingReady=false))
        assertEquals(ConnectionJourney.Action.COPY_ADDRESS,result.action)
    }

    @Test fun publicConnectionRequiresTunnelAndApproval() {
        val notRunning = ConnectionJourney.assess(
            ConnectionJourney.Target.JAVA_ONLINE, ready().copy(playitRunning=false))
        assertEquals(ConnectionJourney.Action.START_PLAYIT, notRunning.action)
        val notConnected = ConnectionJourney.assess(
            ConnectionJourney.Target.JAVA_ONLINE, ready().copy(playitAccountConnected=false))
        assertEquals(ConnectionJourney.Action.CONNECT_PLAYIT, notConnected.action)
    }

    @Test fun bedrockInternetNeedsNethernetSignaling() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.BEDROCK_ONLINE, ready().copy(nethernetSignalingReady=false))
        assertEquals(ConnectionJourney.Action.OPEN_CONSOLE, result.action)
        assertNull(result.address)
    }

    @Test fun externalOnlyNethernetDoesNotMisleadLocalPlayers() {
        val result = ConnectionJourney.assess(ConnectionJourney.Target.BEDROCK_LAN,
            ready().copy(signalingMode="nxs"))
        assertEquals(ConnectionJourney.Action.OPEN_CONSOLE, result.action)
        assertNull(result.address)
        assertTrue(result.title.contains("Local"))
    }

    @Test fun hybridNethernetStillOffersLocalTestAddress() {
        val result = ConnectionJourney.assess(ConnectionJourney.Target.BEDROCK_LAN,
            ready().copy(signalingMode="hybrid"))
        assertEquals("192.168.1.50:19132",result.address)
    }

    @Test fun builtInOnlyNethernetWarnsAboutPlayit() {
        val result = ConnectionJourney.assess(ConnectionJourney.Target.BEDROCK_ONLINE,
            ready().copy(signalingMode="builtin"))
        assertEquals(ConnectionJourney.Action.OPEN_CONSOLE,result.action)
        assertNull(result.address)
    }

    @Test fun publicAddressIsAssignedNotGuaranteed() {
        val result = ConnectionJourney.assess(ConnectionJourney.Target.BEDROCK_ONLINE, ready())
        assertEquals(ConnectionJourney.Action.COPY_ADDRESS, result.action)
        assertEquals("example.playit.gg:19132", result.address)
        assertTrue(result.detail.contains("unverified"))
    }

    @Test fun missingPublicAddressIsNotShareable() {
        val result = ConnectionJourney.assess(
            ConnectionJourney.Target.JAVA_ONLINE, ready().copy(javaPublicAddress=null))
        assertEquals(ConnectionJourney.Action.VIEW_PLAYIT, result.action)
        assertNull(result.address)
    }
}
