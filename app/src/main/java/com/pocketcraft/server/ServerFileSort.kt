package com.pocketcraft.server

import java.io.File
import java.util.Locale

/** Sorting is read-only: no files or server metadata are modified. */
internal object ServerFileSort {
    enum class Mode(val label: String, val shortLabel: String) {
        NAME("Name (A–Z)", "NAME"),
        TYPE("File type (A–Z)", "TYPE"),
        SIZE("Size (largest first)", "SIZE"),
        MODIFIED("Modified (newest first)", "DATE")
    }
    fun fromPreference(saved: String?): Mode =
        Mode.values().firstOrNull { it.name == saved } ?: Mode.NAME

    fun comparator(mode: Mode): Comparator<File> = Comparator { first, second ->
        // Keep directories visible before files in all modes; this is navigation,
        // not a generic filesystem listing where folders might be buried.
        if (first.isDirectory != second.isDirectory)
            return@Comparator if (first.isDirectory) -1 else 1
        if (first.isDirectory)
            return@Comparator first.name.compareTo(second.name, ignoreCase = true)
        val result = when (mode) {
            Mode.NAME -> first.name.compareTo(second.name, ignoreCase = true)
            Mode.TYPE -> first.extension.lowercase(Locale.ROOT)
                .compareTo(second.extension.lowercase(Locale.ROOT))
            Mode.SIZE -> second.length().compareTo(first.length())
            Mode.MODIFIED -> second.lastModified().compareTo(first.lastModified())
        }
        if (result != 0) result else first.name.compareTo(second.name, ignoreCase = true)
    }
}
