package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeyserConfigEditorTest {
    private val sample = """
        bedrock:
          address: 0.0.0.0
          port: 19132
          webrtc-port: 0
          transport: nethernet
          signaling:
            mode: nxs
            mode: builtin
            advertise-addresses: []
            stun-discovery: false
            nxs:
              token: confidential-token-keep
              endpoint: https://nethernet.playit.gg
          advertise-addresses:
            - 147.185.221.216:2476
        java:
          auth-type: floodgate
        gameplay:
          show-coordinates: true
        advanced:
          nxs:
            assisted-joins: false
    """.trimIndent() + "\n"

    @Test fun fixesDuplicateModesAndMisplacedAddressesWithoutDiscardingSecrets() {
        val values = GeyserConfigEditor.read(sample).copy(
            signalingMode = "hybrid",
            advertiseAddresses = "",
            assistedJoins = true
        )
        val result = GeyserConfigEditor.patch(sample, values)
        assertEquals(1, Regex("(?m)^    mode:").findAll(result).count())
        assertTrue(result.contains("    mode: hybrid"))
        assertTrue(result.contains("      token: confidential-token-keep"))
        assertTrue(result.contains("auth-type: floodgate"))
        assertTrue(result.contains("show-coordinates: true"))
        assertTrue(result.contains("assisted-joins: true"))
        assertFalse(result.contains("147.185.221.216:2476"))
        assertEquals(1, Regex("(?m)^    advertise-addresses:").findAll(result).count())
        assertFalse(Regex("(?m)^  advertise-addresses:").containsMatchIn(result))
    }

    @Test fun savesOnlyRequestedTokenReplacement() {
        val settings = GeyserConfigEditor.read(sample).copy(signalingMode = "hybrid")
        val result = GeyserConfigEditor.patch(sample, settings, "replacement-token")
        assertFalse(result.contains("confidential-token-keep"))
        assertTrue(result.contains("token: 'replacement-token'"))
        assertTrue(GeyserConfigEditor.read(result).hasSavedNxsToken)
    }

    @Test fun invalidPortsAreRejectedWithoutTouchingYaml() {
        val values = GeyserConfigEditor.read(sample).copy(transport = "both", webRtcPort = 0)
        assertTrue(GeyserConfigEditor.validate(values)!!.contains("different"))
    }

    @Test fun separateWebRtcPortAndAdvertisedHostsRoundTrip() {
        val values = GeyserConfigEditor.read(sample).copy(
            signalingMode = "hybrid",
            webRtcPort = 19133,
            advertiseAddresses = "mc.example.com:19133, 198.51.100.1:19133"
        )
        val result = GeyserConfigEditor.patch(sample, values)
        val read = GeyserConfigEditor.read(result)
        assertEquals(19133, read.webRtcPort)
        assertEquals("mc.example.com:19133, 198.51.100.1:19133", read.advertiseAddresses)
    }

    @Test fun importRejectsDuplicateBedrockPortsAndNxsTokens() {
        val duplicatePort = sample.replace("  port: 19132",
            "  port: 19132\n  port: 19133")
        // Sample already has two signaling modes, but duplicate port is also independently rejected.
        assertTrue(duplicatePort.contains("port: 19133"))
        assertTrue(GeyserConfigEditor.validateImportedStructure(duplicatePort)!!.contains("bedrock.port"))

        val duplicateToken = """
            bedrock:
              signaling:
                mode: hybrid
                nxs:
                  token: first-secret
                  token: second-secret
        """.trimIndent()
        assertTrue(GeyserConfigEditor.validateImportedStructure(duplicateToken)!!.contains("nxs.token"))
    }

    @Test fun importAcceptsWellFormedSingleModeAndStillKeepsTokenPrivate() {
        val corrected = GeyserConfigEditor.patch(sample,
            GeyserConfigEditor.read(sample).copy(signalingMode = "hybrid"))
        assertEquals(null, GeyserConfigEditor.validateImportedStructure(corrected))
        assertTrue(corrected.contains("token: confidential-token-keep"))
    }

    @Test fun missingGeyserConfigCannotBeSilentlyReplacedWithPartialYaml() {
        val invalid = runCatching { GeyserConfigEditor.patch("java:\n  auth-type: floodgate", GeyserConfigEditor.Values()) }
        assertTrue(invalid.isFailure)
    }
}
