package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeyserNetworkSettingsTest {
    @Test fun readsConfiguredPortTransportAndWebRtc() {
        val settings=GeyserNetworkSettings.parse("""
            bedrock:
              address: 0.0.0.0
              port: 25580
              transport: nethernet
              webrtc-port: 25581
              signaling:
                mode: builtin
            java:
              port: 25565
        """.trimIndent())
        assertEquals(25580,settings.bedrockPort)
        assertEquals(25581,settings.webRtcPort)
        assertEquals("nethernet",settings.transport)
        assertTrue(settings.configured)
        assertTrue(settings.directRouterAdvice().contains("TCP 25580"))
        assertTrue(settings.directRouterAdvice().contains("UDP 25581"))
    }

    @Test fun ignoresUnrelatedOrNestedPorts() {
        val settings=GeyserNetworkSettings.parse("""
            java:
              port: 25565
            bedrock:
              port: 19144
              signaling:
                port: 12345
            advanced:
              port: 43210
        """.trimIndent())
        assertEquals(19144,settings.bedrockPort)
        assertEquals(19144,settings.webRtcPort)
    }

    @Test fun defaultsWhenConfigMissingOrCorrupt() {
        val missing=GeyserNetworkSettings.parse("java:\n  port: 25565")
        assertFalse(missing.configured)
        assertEquals(19132,missing.bedrockPort)
        val invalid=GeyserNetworkSettings.parse("bedrock:\n  port: -1\n  webrtc-port: 99999")
        assertEquals(19132,invalid.bedrockPort)
        assertEquals(19132,invalid.webRtcPort)
    }

    @Test fun zeroWebRtcPortMeansShareBedrockPort() {
        val settings=GeyserNetworkSettings.parse("bedrock:\n  port: 19140\n  transport: nethernet\n  webrtc-port: 0")
        assertEquals(19140,settings.webRtcPort)
    }

    @Test fun raknetRouterAdviceMentionsOnlyUdp() {
        val settings=GeyserNetworkSettings.parse("bedrock:\n  port: 19132\n  transport: raknet")
        assertEquals("UDP 19132 (RakNet)",settings.directRouterAdvice())
    }
}
