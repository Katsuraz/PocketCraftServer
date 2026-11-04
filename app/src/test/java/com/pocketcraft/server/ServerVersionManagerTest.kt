package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerVersionManagerTest {
    @Test fun detectsFabricStartup() {
        assertEquals("26.3", ServerVersionManager.fromLog(
            "[PocketCraft] [12:00:00] [main/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.18.2\n"
        ))
    }

    @Test fun handlesOlderMinecraftRelease() {
        assertEquals("1.21.11", ServerVersionManager.fromLog(
            "[main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.17.3\n"
        ))
    }

    @Test fun choosesLastStartupAfterRestart() {
        assertEquals("26.3", ServerVersionManager.fromLog(
            "Loading Minecraft 1.20.1 with Fabric Loader 0.16.0\n"+
            "Minecraft process exited\n"+
            "Loading Minecraft 26.3 with Fabric Loader 0.18.2\n"
        ))
    }

    @Test fun doesntGuessVersionsFromUnrelatedLogs() {
        assertNull(ServerVersionManager.fromLog(
            "Minecraft 26.3 pack downloaded but server is not launched\n"
        ))
    }
}
