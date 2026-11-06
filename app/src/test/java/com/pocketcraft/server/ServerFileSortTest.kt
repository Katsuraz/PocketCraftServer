package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ServerFileSortTest {
    @Test fun modesArePersistentAndUnknownValuesAreSafe() {
        assertEquals(ServerFileSort.Mode.NAME,ServerFileSort.parse(null))
        assertEquals(ServerFileSort.Mode.NAME,ServerFileSort.parse("../bad"))
        assertEquals(ServerFileSort.Mode.SIZE,ServerFileSort.parse("SIZE"))
        assertEquals(4,ServerFileSort.Mode.entries.size)
    }

    @Test fun foldersAlwaysSortFirstAndFileSortModesWork() {
        val root=Files.createTempDirectory("pc-file-sort").toFile()
        try {
            val folder=java.io.File(root,"z-world").apply {mkdir()}
            val small=java.io.File(root,"a.txt").apply {writeText("a")}
            val large=java.io.File(root,"b.jar").apply {writeText("12345678")}
            small.setLastModified(120000L)
            large.setLastModified(240000L)
            val files=listOf(small,large,folder)
            assertEquals(folder,files.sortedWith(ServerFileSort.comparator(
                ServerFileSort.Mode.NAME)).first())
            assertEquals(listOf(folder,large,small),
                files.sortedWith(ServerFileSort.comparator(ServerFileSort.Mode.SIZE)))
            assertEquals(listOf(folder,large,small),
                files.sortedWith(ServerFileSort.comparator(ServerFileSort.Mode.RECENT)))
            assertEquals(listOf(folder,large,small),
                files.sortedWith(ServerFileSort.comparator(ServerFileSort.Mode.TYPE)))
        } finally {root.deleteRecursively()}
    }
}
