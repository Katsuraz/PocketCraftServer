package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

class ConsoleQuickActionsTest {
    @Test fun defaultsAndPreferenceKeysAreStable() {
        assertEquals(4, ConsoleQuickActions.COUNT)
        assertEquals("list", ConsoleQuickActions.defaults[0].command)
        assertEquals("save-all", ConsoleQuickActions.defaults[1].command)
        assertEquals("console_quick_label_3", ConsoleQuickActions.labelKey(3))
        assertEquals("console_quick_command_3", ConsoleQuickActions.commandKey(3))
    }

    @Test fun savingTrimsAndValidatesCommandsWithoutExecuting() {
        val action = ConsoleQuickActions.validated("  Morning  ", " /time set day ")
        assertEquals("Morning", action.label)
        assertEquals("time set day", action.command)
        assertTrue(ConsoleQuickActions.needsConfirmation(0, action))
        assertFalse(ConsoleQuickActions.needsConfirmation(0, ConsoleQuickActions.defaults[0]))
    }

    @Test fun rejectsEmptyLongOrMultilineValues() {
        assertTrue(runCatching { ConsoleQuickActions.validated("", "list") }.isFailure)
        assertTrue(runCatching { ConsoleQuickActions.validated("x".repeat(25), "list") }.isFailure)
        assertTrue(runCatching { ConsoleQuickActions.validated("one\nanother", "list") }.isFailure)
        assertTrue(runCatching { ConsoleQuickActions.validated("VALID", "say hello\nstop") }.isFailure)
        assertTrue(runCatching { ConsoleQuickActions.validated("VALID", "") }.isFailure)
    }

    @Test fun dangerousRootsRequestConfirmation() {
        for (root in listOf("stop", "op Steve", "ban-ip 127.0.0.1", "fill 1 1 1 2 2 2 air")) {
            assertTrue(ConsoleQuickActions.needsConfirmation(1,
                ConsoleQuickActions.validated("DANGER", root)))
        }
    }
}
