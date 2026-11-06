package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingToolsLayoutTest {
    @Test fun oldInstallsReceiveMinimalLayoutByDefault() {
        assertEquals(HostingToolsLayout.Mode.SIMPLE, HostingToolsLayout.mode(null))
        assertEquals(HostingToolsLayout.Mode.SIMPLE, HostingToolsLayout.mode("invalid"))
    }
    @Test fun allSelectableLayoutsAndActionsAreRetained() {
        assertEquals(3, HostingToolsLayout.Mode.values().size)
        assertEquals(6, HostingToolsLayout.Section.values().size)
        assertEquals(HostingToolsLayout.Mode.CARDS, HostingToolsLayout.mode("cards"))
        assertEquals(HostingToolsLayout.Mode.CLASSIC, HostingToolsLayout.mode("classic"))
        assertTrue(HostingToolsLayout.Section.values().map { it.id }.distinct().size == 6)
    }
    @Test fun sectionVisibilityOnlyFiltersTheHomePresentation() {
        assertEquals(listOf(HostingToolsLayout.Section.FRIENDS,
            HostingToolsLayout.Section.PLAYIT),
            HostingToolsLayout.visible {
                it == HostingToolsLayout.Section.FRIENDS || it == HostingToolsLayout.Section.PLAYIT
            })
        assertEquals(0, HostingToolsLayout.visible { false }.size)
        assertTrue(HostingToolsLayout.Section.values().all {
            HostingToolsLayout.key(it).startsWith("hostingToolsVisible_")
        })
    }
}
