package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayitGeyserImportTest {
    private val original = """
        bedrock:
          address: 0.0.0.0
          port: 19132
          transport: nethernet
          signaling:
            mode: builtin
            advertise-addresses: []
            nxs:
              token: original-private-token
              endpoint: https://agent.warden.cloud
        java:
          auth-type: online
        gameplay:
          show-coordinates: true
        advanced:
          nxs:
            assisted-joins: true
    """.trimIndent() + "\n"

    private val fromPlayit = """
        bedrock:
          address: 0.0.0.0
          port: 19132
          webrtc-port: 0
          advertise-addresses:
            - 147.185.221.216:2476
          transport: nethernet
          clone-remote-port: false
          signaling:
            mode: nxs
            stun-discovery: false
            nxs:
              token: 'private-replacement-token'
              endpoint: https://nethernet.playit.gg
        java:
          auth-type: floodgate
    """.trimIndent() + "\n"

    @Test fun mergesPlayitSnippetAndMovesLegacyAdvertisedEndpointIntoSignaling() {
        val result = GeyserConfigEditor.mergePlayitSnippet(original, fromPlayit)
        assertTrue(result.contains("    mode: nxs"))
        assertTrue(result.contains("    advertise-addresses: ['147.185.221.216:2476']"))
        assertFalse(Regex("(?m)^  advertise-addresses:").containsMatchIn(result))
        assertTrue(result.contains("      token: 'private-replacement-token'"))
        assertFalse(result.contains("original-private-token"))
        assertTrue(result.contains("      endpoint: 'https://nethernet.playit.gg'"))
        assertTrue(result.contains("  auth-type: floodgate"))
        assertTrue(result.contains("show-coordinates: true"))
        assertTrue(result.contains("assisted-joins: true"))
        assertEquals(null, GeyserConfigEditor.validateImportedStructure(result))
    }

    @Test fun oldSavedTokenSurvivesWhenPasteHasNone() {
        val withoutToken = fromPlayit.replace(
            "      token: 'private-replacement-token'\n", "")
        val result = GeyserConfigEditor.mergePlayitSnippet(original, withoutToken)
        assertTrue(result.contains("original-private-token"))
    }

    @Test fun rejectsAmbiguousDuplicateModeAndNeverReportsPrivateToken() {
        val duplicate = fromPlayit.replace("    mode: nxs", "    mode: nxs\n    mode: builtin")
        val failure = runCatching { GeyserConfigEditor.mergePlayitSnippet(original, duplicate) }
        assertTrue(failure.isFailure)
        assertFalse(failure.exceptionOrNull()?.message.orEmpty().contains("private-replacement-token"))
    }

    @Test fun retainsOriginalOnInvalidSnippet() {
        val invalid = runCatching {
            GeyserConfigEditor.mergePlayitSnippet(original, "bedrock:\n  port: 19132\n")
        }
        assertTrue(invalid.isFailure)
        assertTrue(original.contains("original-private-token"))
    }
}
