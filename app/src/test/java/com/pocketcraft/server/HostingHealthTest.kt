package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingHealthTest {
    private fun snapshot(
        fabric: Boolean = true,
        java: Boolean = true,
        eula: Boolean = true,
        geyser: Boolean = false,
        floodgate: Boolean = false,
        running: Boolean = false,
        ready: Boolean = false,
        bedrock: Boolean = false,
        nethernet: Boolean = false,
        playit: Boolean = false,
        javaAddress: String? = null,
        bedrockAddress: String? = null,
        lastError: String? = null
    ) = HostingHealth.Snapshot(fabric,java,eula,geyser,floodgate,
        running,ready,bedrock,nethernet,playit,javaAddress,bedrockAddress,
        "192.168.1.77",lastError)

    @Test fun requiredBlockersAreNotConflatedWithOptionalCrossplay() {
        assertEquals(0, HostingHealth.startBlockers(snapshot()))
        assertEquals(3, HostingHealth.startBlockers(snapshot(
            fabric=false,java=false,eula=false)))
        assertEquals(0, HostingHealth.startBlockers(snapshot(
            geyser=false,playit=false)))
    }

    @Test fun anAgentAndPublicAddressDoNotClaimJavaServerReadiness() {
        val checks = HostingHealth.evaluate(snapshot(
            playit=true,javaAddress="test.playit.gg:30223"))
        val java = checks.first { it.title=="Java multiplayer" }
        val remote = checks.first { it.title=="Remote Java / Playit" }
        assertFalse(java.level == HostingHealth.Level.GOOD)
        assertTrue(remote.explanation.contains("Test from another network"))
    }

    @Test fun bedrockJarIsNotEquivalentToActiveGeyserListener() {
        val checks=HostingHealth.evaluate(snapshot(geyser=true,floodgate=true))
        assertEquals(HostingHealth.Level.INFO,
            checks.first { it.title=="Bedrock bridge" }.level)
        assertEquals(HostingHealth.Level.GOOD,
            checks.first { it.title=="Floodgate" }.level)
    }

    @Test fun nethernetAndBedrockRequireSeparateSignals() {
        val checks=HostingHealth.evaluate(snapshot(
            bedrock=true,nethernet=false,bedrockAddress="nethernet.example.org"))
        assertTrue(checks.first { it.title=="Remote Bedrock / NetherNet" }
            .explanation.contains("not confirmed"))
    }

    @Test fun lastServerErrorIsActionableNotHidden() {
        val checks=HostingHealth.evaluate(snapshot(lastError="JVM failed"))
        assertEquals(HostingHealth.Level.ACTION,checks.last().level)
        assertTrue(checks.last().explanation.contains("JVM failed"))
    }
}
