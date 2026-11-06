package com.pocketcraft.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatapackInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun pendingWorldAllowsDatapacksBeforeFirstLaunch() {
        val world = temp.newFolder("woodland")
        java.io.File(world, ".pocketcraft-pending-world").writeText("Reserved")
        assertTrue(DatapackInstaller.isEligibleWorld(world))
    }

    @Test fun ordinaryFolderIsNotAWorld() {
        val directory = temp.newFolder("random")
        assertFalse(DatapackInstaller.isEligibleWorld(directory))
    }

    @Test fun generatedWorldAllowsDatapacks() {
        val world = temp.newFolder("survival")
        java.io.File(world, "level.dat").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(DatapackInstaller.isEligibleWorld(world))
    }
}
