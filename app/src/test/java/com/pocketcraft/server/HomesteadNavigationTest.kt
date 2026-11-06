package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomesteadNavigationTest {
    @Test fun bottomDockKeepsExistingMainActivityDestinations() {
        assertEquals(listOf("Home","Worlds","Mods","Console"),HomesteadNavigation.labels)
        assertEquals(listOf(0,2,1,3),(0..3).map { HomesteadNavigation.pageForSlot(it) })
        for(slot in 0..3) {
            val page=HomesteadNavigation.pageForSlot(slot)
            assertEquals(slot,HomesteadNavigation.slotForPage(page))
            assertEquals(HomesteadNavigation.labels[slot],HomesteadNavigation.label(page))
        }
    }

    @Test fun unknownDestinationIsSafelyHandled() {
        assertEquals(0,HomesteadNavigation.pageForSlot(-1))
        assertEquals(0,HomesteadNavigation.pageForSlot(9))
        assertEquals(0,HomesteadNavigation.slotForPage(-1))
        assertEquals("Home",HomesteadNavigation.label(9))
        assertEquals(3,HomesteadNavigation.validTab(500))
    }

    @Test fun allServerSettingsSectionsHaveOneCategory() {
        val pairs=mapOf(
            "Appearance" to "Appearance",
            "Mojangles font" to "Appearance",
            "Sound effects" to "Appearance",
            "App updates" to "System",
            "Server connections" to "Connections",
            "Bedrock & Geyser" to "Connections",
            "Who can join?" to "Access",
            "Server resources" to "System"
        )
        for ((section,category) in pairs) {
            assertEquals(category,HomesteadNavigation.settingsCategory(section))
        }
        assertEquals(4,HomesteadNavigation.settingsCategories.distinct().size)
        assertTrue(pairs.values.all { it in HomesteadNavigation.settingsCategories })
    }
}
