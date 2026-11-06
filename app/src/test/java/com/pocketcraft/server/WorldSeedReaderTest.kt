package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

class WorldSeedReaderTest {
    @get:Rule val temp = TemporaryFolder()

    private fun writeSeed(file: File, nested: Boolean, seed: Long) {
        file.parentFile?.mkdirs()
        DataOutputStream(GZIPOutputStream(file.outputStream())).use { out ->
            out.writeByte(10)
            out.writeUTF("")
            out.writeByte(10)
            out.writeUTF("Data")
            if (nested) {
                out.writeByte(10)
                out.writeUTF("WorldGenSettings")
            }
            out.writeByte(4)
            out.writeUTF("seed")
            out.writeLong(seed)
            out.writeByte(0)
            if (nested) out.writeByte(0)
            out.writeByte(0)
        }
    }

    @Test fun extractsSeedFromModern26xSavedData() {
        val world = temp.newFolder("modern")
        val modern = File(world, "data/minecraft/world_gen_settings.dat")
        writeSeed(modern, false, -913271976544991L)
        assertEquals(-913271976544991L, WorldSeedReader.readSeed(world))
    }

    @Test fun legacyWorldGenSettingsSeedIsSupported() {
        val world = temp.newFolder("legacy")
        writeSeed(File(world, "level.dat"), true, 221188441L)
        assertEquals(221188441L, WorldSeedReader.readSeed(world))
    }

    @Test fun modernSeedTakesPriorityOverStaleLegacyFile() {
        val world = temp.newFolder("mixed")
        writeSeed(File(world, "level.dat"), true, 1L)
        writeSeed(File(world, "data/minecraft/world_gen_settings.dat"), false, 2L)
        assertEquals(2L, WorldSeedReader.readSeed(world))
    }

    @Test(expected = IOException::class)
    fun rejectsWorldWhenSeedCannotBeVerified() {
        val world = temp.newFolder("invalid")
        File(world, "level.dat").writeBytes(byteArrayOf(1, 2, 3))
        WorldSeedReader.readSeed(world)
    }
}
