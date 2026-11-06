package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ModpackWorldDatapackPathsTest {
    @Test fun packsWorldDatapacksIntoSelectedWorld() {
        assertEquals("Terralith.zip",
            ModpackImporter.worldDatapackPath("world/datapacks/Terralith.zip"))
        assertEquals("Terralith.zip",
            ModpackImporter.worldDatapackPath("datapacks/Terralith.zip"))
    }

    @Test fun leavesServerModsUnaffected() {
        assertNull(ModpackImporter.worldDatapackPath("mods/lithium.jar"))
    }

    @Test(expected = IOException::class)
    fun refusesWorldDatapackTraversal() {
        ModpackImporter.worldDatapackPath("world/datapacks/../../server.properties")
    }
}
