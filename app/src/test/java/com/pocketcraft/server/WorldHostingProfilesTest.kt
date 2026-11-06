package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldHostingProfilesTest {
    @Test fun newWorldGetsSafeDefaults() {
        val profile = WorldHostingProfiles.defaults()
        assertEquals("normal",profile.getProperty("difficulty"))
        assertEquals("survival",profile.getProperty("gamemode"))
        assertEquals("5",profile.getProperty("view-distance"))
        assertNull(WorldHostingProfiles.validate(profile))
    }

    @Test fun eachWorldUpdateCanBeMadeIndependently() {
        val first = WorldHostingProfiles.update(WorldHostingProfiles.defaults(),
            mapOf("difficulty" to "hard","motd" to "First world","view-distance" to "4"))
        val second = WorldHostingProfiles.update(WorldHostingProfiles.defaults(),
            mapOf("difficulty" to "peaceful","motd" to "Second world","view-distance" to "7"))
        assertEquals("hard", first.getProperty("difficulty"))
        assertEquals("peaceful", second.getProperty("difficulty"))
        assertEquals("4", first.getProperty("view-distance"))
        assertEquals("7", second.getProperty("view-distance"))
    }

    @Test fun invalidPreferencesAndUnsupportedKeysCannotBeSaved() {
        val rejected = runCatching {
            WorldHostingProfiles.update(WorldHostingProfiles.defaults(),
                mapOf("view-distance" to "1"))
        }
        assertTrue(rejected.isFailure)
    }

    @Test fun aWorldWithoutAProfileInheritsExistingServerSettings() {
        val runningConfig = java.util.Properties().apply {
            setProperty("difficulty", "hard")
            setProperty("gamemode", "creative")
            setProperty("pvp", "false")
            setProperty("view-distance", "12")
            setProperty("online-mode", "true")
            setProperty("level-name", "new-world")
        }
        WorldHostingProfiles.applySavedProfile(runningConfig, null)
        assertEquals("hard", runningConfig.getProperty("difficulty"))
        assertEquals("creative", runningConfig.getProperty("gamemode"))
        assertEquals("false", runningConfig.getProperty("pvp"))
        assertEquals("12", runningConfig.getProperty("view-distance"))
        assertEquals("true", runningConfig.getProperty("online-mode"))
    }

    @Test fun aWorldWithAnExplicitProfileOverridesOnlyWorldSpecificKeys() {
        val runningConfig = java.util.Properties().apply {
            setProperty("difficulty", "hard")
            setProperty("gamemode", "creative")
            setProperty("online-mode", "true")
            setProperty("max-players", "8")
        }
        val saved = WorldHostingProfiles.update(WorldHostingProfiles.defaults(),
            mapOf("difficulty" to "peaceful", "gamemode" to "survival", "pvp" to "false"))
        WorldHostingProfiles.applySavedProfile(runningConfig, saved)
        assertEquals("peaceful", runningConfig.getProperty("difficulty"))
        assertEquals("survival", runningConfig.getProperty("gamemode"))
        assertEquals("false", runningConfig.getProperty("pvp"))
        assertEquals("true", runningConfig.getProperty("online-mode"))
        assertEquals("8", runningConfig.getProperty("max-players"))
    }

    @Test fun anInvalidSavedProfileCannotChangeServerSettings() {
        val runningConfig = java.util.Properties().apply { setProperty("difficulty", "hard") }
        val saved = WorldHostingProfiles.defaults().apply { setProperty("view-distance", "0") }
        assertTrue(runCatching {
            WorldHostingProfiles.applySavedProfile(runningConfig, saved)
        }.isFailure)
        assertEquals("hard", runningConfig.getProperty("difficulty"))
    }

    @Test fun newlineAndUnauthorizedPropertiesAreRejected() {
        val profile = WorldHostingProfiles.defaults()
        profile.setProperty("level-seed","bad\nseed")
        assertNotNull(WorldHostingProfiles.validate(profile))
        val failed = runCatching {
            WorldHostingProfiles.update(WorldHostingProfiles.defaults(),
                mapOf("online-mode" to "false"))
        }
        assertTrue(failed.isFailure)
    }
}
