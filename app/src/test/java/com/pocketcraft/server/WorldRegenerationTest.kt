package com.pocketcraft.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorldRegenerationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun preservesDatapackZipAndDirectoryWithoutWorldProgress() {
        val source = temp.newFolder("old")
        val destination = temp.newFolder("new")
        val packs = File(source, "datapacks")
        val nested = File(packs, "custom/data/example")
        assertTrue(nested.mkdirs())
        val pack = File(packs, "Terralith.zip")
        val bytes = byteArrayOf(0x50,0x4b,3,4,11,12)
        pack.writeBytes(bytes)
        File(nested, "functions.mcfunction").writeText("say hello")
        File(source, "level.dat").writeBytes(byteArrayOf(5))
        File(source, "playerdata").mkdir()
        File(source, "playerdata/example.dat").writeText("old inventory")
        val count = WorldRegeneration.copyDatapacks(source, destination)
        assertEquals(2, count)
        assertArrayEquals(bytes, File(destination,"datapacks/Terralith.zip").readBytes())
        assertEquals("say hello", File(destination,
            "datapacks/custom/data/example/functions.mcfunction").readText())
        assertFalse(File(destination, "level.dat").exists())
        assertFalse(File(destination, "playerdata").exists())
    }

    @Test fun worksWithNoDatapacks() {
        val src=temp.newFolder("source")
        val target=temp.newFolder("target")
        assertEquals(0, WorldRegeneration.copyDatapacks(src,target))
    }
}
