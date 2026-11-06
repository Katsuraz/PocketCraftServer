package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayitAddressResolverTest {
    @Test fun nethernetUdpAllocationIsNotPresentedAsBedrockJoinPort() {
        val resolved = PlayitAddressResolver.resolve(
            "nethernet", "jensen-unmovable.tun.ply.gg:2476")
        assertEquals("jensen-unmovable.tun.ply.gg:19132", resolved.joinAddress)
        assertEquals("jensen-unmovable.tun.ply.gg:2476", resolved.reportedEndpoint)
    }

    @Test fun knownCorrectNethernetAddressIsNotDuplicated() {
        val resolved = PlayitAddressResolver.resolve(
            "nethernet", "jensen-unmovable.tun.ply.gg:19132")
        assertEquals("jensen-unmovable.tun.ply.gg:19132", resolved.joinAddress)
        assertNull(resolved.reportedEndpoint)
    }

    @Test fun nethernetHostnameWithoutPortGetsOfficialJoinPort() {
        val resolved = PlayitAddressResolver.resolve(
            "nethernet", "jensen-unmovable.tun.ply.gg")
        assertEquals("jensen-unmovable.tun.ply.gg:19132", resolved.joinAddress)
    }

    @Test fun javaAndLegacyBedrockKeepTheirAllocatedPorts() {
        for (type in listOf("minecraft-java", "minecraft-bedrock")) {
            val resolved = PlayitAddressResolver.resolve(type, "example.tun.ply.gg:2476")
            assertEquals("example.tun.ply.gg:2476", resolved.joinAddress)
            assertNull(resolved.reportedEndpoint)
        }
    }

    @Test fun malformedOrIpv6AddressesAreNeverRewritten() {
        for (input in listOf("bad:99999", "[2001:db8::2]:2476",
            "https://example.tun.ply.gg:2476", "example.tun.ply.gg:0")) {
            assertEquals(input, PlayitAddressResolver.resolve("nethernet", input).joinAddress)
        }
    }
}
