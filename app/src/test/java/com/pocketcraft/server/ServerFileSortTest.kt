package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerFileSortTest {
    @get:Rule val folder=TemporaryFolder()

    @Test fun invalidPreferenceFallsBackToName() {
        assertEquals(ServerFileSort.Mode.NAME,ServerFileSort.fromPreference("unknown"))
        assertEquals(ServerFileSort.Mode.MODIFIED,ServerFileSort.fromPreference("MODIFIED"))
    }

    @Test fun directoriesComeBeforeFilesRegardlessOfSort() {
        val dir=folder.newFolder("config")
        val zip=folder.newFile("backup.zip").apply {writeText("very large")}
        for(mode in ServerFileSort.Mode.values()) {
            assertEquals(dir,listOf(zip,dir).sortedWith(ServerFileSort.comparator(mode)).first())
        }
    }

    @Test fun sortsByExtensionAndNameDeterministically() {
        val json=folder.newFile("alpha.json")
        val toml=folder.newFile("beta.toml")
        val config=folder.newFile("zeta.json")
        assertEquals(listOf(json,config,toml),
            listOf(toml,config,json).sortedWith(ServerFileSort.comparator(ServerFileSort.Mode.TYPE)))
    }

    @Test fun sortsByLargestAndNewest() {
        val small=folder.newFile("small.txt").apply {writeText("a")}
        val big=folder.newFile("big.txt").apply {writeText("more bytes")}
        assertEquals(big,listOf(small,big).sortedWith(
            ServerFileSort.comparator(ServerFileSort.Mode.SIZE)).first())
        assertTrue(small.setLastModified(System.currentTimeMillis()-100000))
        assertTrue(big.setLastModified(System.currentTimeMillis()))
        assertEquals(big,listOf(small,big).sortedWith(
            ServerFileSort.comparator(ServerFileSort.Mode.MODIFIED)).first())
    }
}
