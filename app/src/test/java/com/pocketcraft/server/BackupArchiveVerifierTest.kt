package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveVerifierTest {
    private fun archive(vararg names: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { b ->
            ZipOutputStream(b).use { zip ->
                names.forEach { (name,content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    @Test fun verifiesValidArchiveAndReportsActualExpandedSize() {
        val bytes=archive("world/level.dat" to "level", "mods/example.jar" to "abc")
        val result=BackupArchiveVerifier.verify(ByteArrayInputStream(bytes))
        assertEquals(2,result.files)
        assertEquals(8L,result.expandedBytes)
    }
    @Test fun rejectsZipTraversalAndAbsolutePaths() {
        for(path in listOf("../secret.txt","folder/../secret.txt","/secrets","C:/foo")) {
            val result=runCatching {
                BackupArchiveVerifier.verify(ByteArrayInputStream(archive(path to "bad")))
            }
            assertTrue(path,result.isFailure)
        }
    }
    @Test fun rejectsCorruptAndEmptyArchives() {
        assertTrue(runCatching {
            BackupArchiveVerifier.verify(ByteArrayInputStream(archive()))
        }.isFailure)
        val bytes=archive("world/level.dat" to "data")
        // Mutate bytes in the local data field to corrupt its ZIP CRC.
        val modified=bytes.clone()
        val marker="data".toByteArray()
        val pos=modified.indices.firstOrNull { k ->
            k+marker.size<=modified.size && marker.indices.all { i -> modified[k+i]==marker[i] }
        }
        if(pos!=null) {
            modified[pos]=(modified[pos].toInt() xor 0x1).toByte()
            assertTrue(runCatching {
                BackupArchiveVerifier.verify(ByteArrayInputStream(modified))
            }.isFailure)
        }
    }
    @Test fun rejectsConflictingFileAndFolderPaths() {
        assertTrue(runCatching {
            BackupArchiveVerifier.verify(ByteArrayInputStream(
                archive("world" to "file", "world/level.dat" to "data")))
        }.isFailure)
    }
}
