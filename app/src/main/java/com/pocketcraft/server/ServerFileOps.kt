package com.pocketcraft.server

import java.io.File
import java.io.IOException

/**
 * Sandbox server file operations to the existing PocketCraft Minecraft folder.
 * Never alter worlds, JARs or sensitive binary files through the text editor.
 */
internal object ServerFileOps {
    const val MAX_EDIT_BYTES = 262_144L
    private val extensions = setOf(
        "yml", "yaml", "json", "properties", "toml", "txt",
        "log", "cfg", "conf", "md", "mcmeta", "ini"
    )

    fun checked(root: File, selected: File): File {
        val boundary = root.canonicalFile
        val file = selected.canonicalFile
        if (file != boundary && !file.path.startsWith(boundary.path + File.separator)) {
            throw IOException("File is outside the PocketCraft server folder")
        }
        return file
    }

    fun isEditable(file: File): Boolean =
        file.isFile && !file.name.startsWith(".") &&
            file.extension.lowercase() in extensions &&
            file.length() <= MAX_EDIT_BYTES

    fun readText(root: File, selected: File): String {
        val file = checked(root, selected)
        if (!isEditable(file)) {
            throw IOException("This file is not a small text configuration")
        }
        return file.readText(Charsets.UTF_8)
    }

    fun saveText(root: File, selected: File, content: String): File {
        val file = checked(root, selected)
        if (!isEditable(file)) throw IOException("File cannot be edited safely")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_EDIT_BYTES) {
            throw IOException("Edited configuration is too large")
        }
        val backup = File(file.parentFile, file.name + ".pocketcraft-" +
            System.currentTimeMillis() + ".bak")
        val staging = File(file.parentFile, "." + file.name + ".pocketcraft-writing")
        checked(root, backup)
        checked(root, staging)
        if (!file.copyTo(backup, overwrite = false).isFile) {
            throw IOException("Could not back up configuration")
        }
        try {
            staging.writeText(content, Charsets.UTF_8)
            // Rename on the same filesystem; restore backup if activating fails.
            if (!staging.renameTo(file)) {
                if (!backup.copyTo(file, overwrite = true).isFile) {
                    throw IOException("Could not restore the original configuration")
                }
                throw IOException("Could not save configuration")
            }
        } finally {
            staging.delete()
        }
        return backup
    }

    fun restore(root: File, original: File, backup: File) {
        val target = checked(root, original)
        val source = checked(root, backup)
        if (!source.isFile || !source.name.startsWith(target.name + ".pocketcraft-") ||
            !source.name.endsWith(".bak")) {
            throw IOException("Not a backup of this file")
        }
        if (source.length() > MAX_EDIT_BYTES) throw IOException("Backup too large")
        source.copyTo(target, overwrite = true)
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
