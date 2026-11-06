package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

class PocketThemesTest {
    @Test fun tenCompleteUniqueThemes() {
        assertEquals(10,PocketThemes.themes.size)
        assertEquals(10,PocketThemes.themes.map { it.id }.toSet().size)
        for (theme in PocketThemes.themes) {
            assertEquals(0xff,PocketThemes.palette(theme.id).bg ushr 24)
            assertNotEquals(theme.palette.bg,theme.palette.text)
            assertTrue(theme.name.isNotBlank())
        }
    }
    @Test fun upgradesKeepBothExistingThemeIds() {
        assertEquals("homestead",PocketThemes.selected(null).id)
        assertEquals("classic",PocketThemes.selected("classic").id)
        assertEquals("homestead",PocketThemes.selected("unknown").id)
    }
    @Test fun customAccentAcceptsOnlyOpaqueRgb() {
        assertEquals(0xff2468a0.toInt(),PocketThemes.parseAccent("#2468A0"))
        assertNull(PocketThemes.parseAccent("#ZZ9933"))
        assertNull(PocketThemes.parseAccent("00000000"))
    }
}
