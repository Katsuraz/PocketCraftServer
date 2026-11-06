package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ServerRestoreCommitTest {
    private fun folder(): File = Files.createTempDirectory("pocketcraft-test").toFile()
    @Test fun movesAllFilesForSuccessfulRestore() {
        val temp=folder()
        try {
            val stage=File(temp,"staged").apply { mkdir() }
            val root=File(temp,"server").apply { mkdir() }
            File(stage,"world/level.dat").apply { parentFile.mkdirs();writeText("world") }
            File(stage,"mods/mod.jar").apply { parentFile.mkdirs();writeText("mod") }
            assertEquals(2,ServerRestoreCommit.apply(stage,root))
            assertEquals("world",File(root,"world/level.dat").readText())
            assertEquals("mod",File(root,"mods/mod.jar").readText())
        } finally {temp.deleteRecursively()}
    }
    @Test fun failureDuringSecondMoveRollsBackAllNewServerFiles() {
        val temp=folder()
        try {
            val stage=File(temp,"staged").apply { mkdir() }
            val root=File(temp,"server").apply { mkdir() }
            File(stage,"first.txt").writeText("one")
            File(stage,"second.txt").writeText("two")
            var moved=0
            val failure=runCatching {
                ServerRestoreCommit.apply(stage,root) { a,b ->
                    moved++
                    if(moved==2) false else a.renameTo(b)
                }
            }
            assertTrue(failure.isFailure)
            assertFalse(File(root,"first.txt").exists())
            assertFalse(File(root,"second.txt").exists())
        } finally {temp.deleteRecursively()}
    }
    @Test fun existingServerFileCannotBeOverwritten() {
        val temp=folder()
        try {
            val stage=File(temp,"staged").apply { mkdir() }
            val root=File(temp,"server").apply { mkdir() }
            File(stage,"server.properties").writeText("new")
            File(root,"server.properties").writeText("original")
            assertTrue(runCatching { ServerRestoreCommit.apply(stage,root) }.isFailure)
            assertEquals("original",File(root,"server.properties").readText())
        } finally {temp.deleteRecursively()}
    }
}
