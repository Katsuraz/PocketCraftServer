package com.pocketcraft.server

import java.io.File
import java.util.Locale

/** Explicit, persistent sort policy for the safe app-private file browser. */
internal object ServerFileSort {
    enum class Mode(val label: String) {
        NAME("Name A–Z"), TYPE("File type"), SIZE("Size (largest first)"),
        RECENT("Last modified (newest first)")
    }
    fun parse(value: String?): Mode =
        Mode.entries.firstOrNull { it.name == value } ?: Mode.NAME

    /** Directories precede files; ties are stable and alphabetic. */
    fun comparator(mode: Mode): Comparator<File> = Comparator { a,b ->
        if (a.isDirectory != b.isDirectory) return@Comparator if (a.isDirectory) -1 else 1
        val result = when(mode) {
            Mode.NAME -> a.name.compareTo(b.name,ignoreCase=true)
            Mode.TYPE -> a.extension.compareTo(b.extension,ignoreCase=true)
            Mode.SIZE -> b.length().compareTo(a.length())
            Mode.RECENT -> b.lastModified().compareTo(a.lastModified())
        }
        if(result!=0)result else a.name.lowercase(Locale.ROOT)
            .compareTo(b.name.lowercase(Locale.ROOT))
    }
}
