package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

class UpdateChannelsTest {
    @Test fun stableExcludesBetaAndRc() {
        assertFalse(UpdateChannels.accepts(UpdateChannels.STABLE,true))
        assertFalse(UpdateChannels.accepts(null,true))
        assertTrue(UpdateChannels.accepts(UpdateChannels.STABLE,false))
    }
    @Test fun optedInBetaAlsoReceivesStable() {
        assertTrue(UpdateChannels.accepts(UpdateChannels.BETA,true))
        assertTrue(UpdateChannels.accepts(UpdateChannels.BETA,false))
    }
    @Test fun buildVersionDistinguishesValidBetaTags() {
        assertTrue(UpdateChannels.isBetaVersion("0.9.0-beta.1"))
        assertTrue(UpdateChannels.isBetaVersion("0.9.0-rc.2"))
        assertFalse(UpdateChannels.isBetaVersion("0.9.0"))
        assertFalse(UpdateChannels.isBetaVersion("v0.9.0-beta.1"))
    }
}
