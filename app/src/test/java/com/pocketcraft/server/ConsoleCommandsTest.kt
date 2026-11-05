package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConsoleCommandsTest {
    @Test fun acceptsServerAndInGameCommands() {
        assertEquals("list", ConsoleCommands.normalize("list"))
        assertEquals("help", ConsoleCommands.normalize("/help"))
        assertEquals("time set day", ConsoleCommands.normalize("  /time set day  "))
        assertEquals("function vanilla_refresh:_settings",
            ConsoleCommands.normalize("/function vanilla_refresh:_settings"))
    }

    @Test fun keepsSpaceInArguments() {
        assertEquals("say Hello Bedrock players!", ConsoleCommands.normalize("say Hello Bedrock players!"))
    }

    @Test fun rejectsBlankAndMultipleCommands() {
        assertThrows(IllegalArgumentException::class.java) { ConsoleCommands.normalize("  /  ") }
        assertThrows(IllegalArgumentException::class.java) { ConsoleCommands.normalize("list\nstop") }
        assertThrows(IllegalArgumentException::class.java) { ConsoleCommands.normalize("list\rstop") }
        assertThrows(IllegalArgumentException::class.java) { ConsoleCommands.normalize("list\u0000stop") }
    }

    @Test fun rejectsExcessivelyLongCommands() {
        assertEquals(240, ConsoleCommands.normalize("a".repeat(240)).length)
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleCommands.normalize("a".repeat(241))
        }
    }
}
