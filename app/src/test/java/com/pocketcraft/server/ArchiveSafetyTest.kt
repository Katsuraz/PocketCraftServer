package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class ArchiveSafetyTest {
    @Test fun acceptsTarRootEntries() {
        assertNull(ArchiveSafety.relativeEntry("."))
        assertNull(ArchiveSafety.relativeEntry("./"))
        assertEquals("bin/java", ArchiveSafety.relativeEntry("./bin/java"))
        assertEquals("jre/bin/java", ArchiveSafety.relativeEntry("./jre/./bin/java"))
        assertEquals("lib/security/cacerts", ArchiveSafety.relativeEntry("lib//security/cacerts"))
    }

    @Test fun blocksTraversalAndAbsolutePaths() {
        listOf(
            "../escape", "./../escape", "bin/../../escape",
            "/etc/passwd", "C:/windows/system32", "bin\\java"
        ).forEach { attack ->
            assertThrows("Unexpected acceptance: $attack", IOException::class.java) {
                ArchiveSafety.relativeEntry(attack)
            }
        }
    }

    @Test fun permitsOnlyPathsWithinDestination() {
        val root = Files.createTempDirectory("pocketcraft-jre-test").toFile()
        try {
            assertEquals(root.resolve("lib/server"), ArchiveSafety.within(root, "lib/server"))
            assertThrows(IOException::class.java) { ArchiveSafety.within(root, "../outside") }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun validatesSymlinkDestination() {
        val root = Files.createTempDirectory("pocketcraft-link-test").toFile()
        try {
            val parent = root.resolve("bin").also { it.mkdirs() }
            assertEquals(root.resolve("lib").canonicalFile,
                ArchiveSafety.checkedSymlinkTarget(root, parent, "../lib"))
            assertThrows(IOException::class.java) {
                ArchiveSafety.checkedSymlinkTarget(root, parent, "../../escape")
            }
            assertThrows(IOException::class.java) {
                ArchiveSafety.checkedSymlinkTarget(root, parent, "/system/bin")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
