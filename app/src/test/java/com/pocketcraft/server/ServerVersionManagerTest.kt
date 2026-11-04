package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerVersionManagerTest {
    @Test fun readsFabricVersionFromActualStartupLine() {
        assertEquals("26.3",ServerVersionManager.fromLog(
            "[22:04:02] [main/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.19.5\n"
        ))
    }

    @Test fun ignoresUnrelatedServerLines() {
        assertNull(ServerVersionManager.fromLog(
            "Starting minecraft server version 1.20.1\nDone (2.15s)! For help, type help\n"
        ))
    }

    @Test fun usesLatestFabricStartupAfterRestart() {
        assertEquals("1.21.11",ServerVersionManager.fromLog(
            "Loading Minecraft 1.20.1 with Fabric Loader 0.16.5\n"+
            "Other log lines\nLoading Minecraft 1.21.11 with Fabric Loader 0.17.3\n"
        ))
    }

    @Test fun rejectsUnrelatedStrings() {
        assertNull(ServerVersionManager.fromLog("Fabric server not detected; modpack 26.3"))
    }
}
