package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

/** Same selection rules for every Modrinth parent/child project. */
class ModrinthDependencySelectionTest {
    @Test fun publishedPinIsDefaultButUserCanOverrideForAnyMod() {
        val pinned=mapOf("XaDC71GB" to "customLithostitched",
            "P7dR8mSH" to "customFabricApi")
        assertEquals("authorPin",ModrinthDependencySelection.choose(
            "OtherMod","authorPin",pinned,emptySet()))
        assertEquals("customLithostitched",ModrinthDependencySelection.choose(
            "XaDC71GB","deadPin",pinned,emptySet()))
        assertEquals("customFabricApi",ModrinthDependencySelection.choose(
            "P7dR8mSH",null,pinned,emptySet()))
    }

    @Test fun autoCompatibleIntentionallyBypassesBrokenPinsForAnyProject() {
        assertNull(ModrinthDependencySelection.choose("XaDC71GB","deletedPin",
            emptyMap(),setOf("XaDC71GB")))
        assertNull(ModrinthDependencySelection.choose("anotherLibrary","oldPin",
            emptyMap(),setOf("anotherLibrary")))
        assertNull(ModrinthDependencySelection.choose("freshLibrary",null,
            emptyMap(),emptySet()))
    }

    @Test fun oneProjectChoiceCannotChangeAnotherDependentMod() {
        val pinned=mapOf("A12345" to "altVersion")
        assertEquals("altVersion",ModrinthDependencySelection.choose(
            "A12345","original",pinned,emptySet()))
        assertEquals("original",ModrinthDependencySelection.choose(
            "B12345","original",pinned,emptySet()))
    }

    @Test fun sameProjectCannotHaveConflictingAutoAndExactChoices() {
        try {
            ModrinthDependencySelection.choose("A123","oldPin",
                mapOf("A123" to "exact"),setOf("A123"))
            fail("Conflicting dependency selections must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("both"))
        }
    }

    @Test fun maliciousProjectIdIsRejectedBeforeApiLookup() {
        assertThrows(IllegalArgumentException::class.java) {
            ModrinthDependencySelection.choose("../not-a-project",
                "x",emptyMap(),emptySet())
        }
    }
}
