package com.pocketcraft.server

import java.io.File
import java.io.IOException

/**
 * Validate paths from an untrusted tar archive before creating files.
 * tar archives commonly contain a harmless "." root entry and "./" prefixes.
 */
internal object ArchiveSafety {
    /** Returns null only for the archive's own root directory. */
    fun relativeEntry(raw: String): String? {
        if (raw.startsWith("/") || raw.startsWith("\\") ||
            raw.indexOf('\u0000') >= 0 || raw.contains('\\') ||
            Regex("^[A-Za-z]:").containsMatchIn(raw)) {
            throw IOException("Unsafe JRE archive path: invalid absolute entry")
        }

        val parts = raw.split('/')
            .filter { it.isNotEmpty() && it != "." }

        if (parts.any { it == ".." }) {
            throw IOException("Unsafe JRE archive path: directory traversal")
        }
        // tar -C <folder> -cf archive.tar . produces "." and "./" directory entries.
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    /** Canonical validation also protects against paths routed via extracted symlinks. */
    fun within(root: File, relative: String): File {
        val base = root.canonicalFile
        val dest = File(root, relative)
        if (!dest.canonicalPath.startsWith(base.path + File.separator)) {
            throw IOException("Unsafe JRE archive path: outside installation directory")
        }
        return dest
    }

    fun checkedSymlinkTarget(root: File, linkParent: File, linkName: String): File {
        if (linkName.isBlank() || linkName.startsWith("/") || linkName.contains('\\') ||
            Regex("^[A-Za-z]:").containsMatchIn(linkName)) {
            throw IOException("Unsafe JRE symlink: invalid target")
        }
        val target = File(linkParent, linkName).canonicalFile
        if (!target.path.startsWith(root.canonicalPath + File.separator)) {
            throw IOException("Unsafe JRE symlink: target outside installation directory")
        }
        return target
    }
}
