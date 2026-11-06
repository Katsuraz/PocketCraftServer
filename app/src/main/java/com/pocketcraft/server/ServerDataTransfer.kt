package com.pocketcraft.server

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export or restore the whole Minecraft server folder before a signature migration.
 * The Android Java runtime and Playit agent secret live outside this folder and
 * are deliberately not included. Restore refuses to overwrite any existing data.
 */
internal object ServerDataTransfer {
    private const val MAX_FILES = 50_000
    private const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024

    fun backup(context: Context, uri: Uri): Int {
        if (ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
            throw IOException("Stop Minecraft before creating a backup")
        val root = WorldStore.root(context).canonicalFile
        val files = root.walkTopDown().filter { it.isFile }.toList()
        if (files.size > MAX_FILES) throw IOException("Too many server files to export")
        var count = 0
        val out = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Cannot create backup document")
        out.use { stream ->
            ZipOutputStream(stream).use { zip ->
                for (file in files) {
                    ServerFileOps.checked(root, file)
                    // Prevent accidentally copying a self-referential symlink.
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel.isBlank() || rel.contains("..")) throw IOException("Invalid backup entry")
                    zip.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zip, 32 * 1024) }
                    zip.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    fun restore(context: Context, uri: Uri): Int {
        if (ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
            throw IOException("Stop Minecraft before restoring")
        val root = WorldStore.root(context).canonicalFile
        // Never silently overwrite an existing world, modpack, or config.
        // PocketCraft creates an empty mods/ directory on first launch;
        // that must not prevent restoring a fresh install.
        if (root.walkTopDown().any { it.isFile }) {
            throw IOException("Restore requires a new server without existing files. Export the current server first; no files will be overwritten.")
        }
        // Verify all CRCs, declared paths and size limits without altering the server.
        // The document is opened again for staging after this read-only check.
        val info = context.contentResolver.openInputStream(uri)?.use {
            BackupArchiveVerifier.verify(it)
        } ?: throw IOException("Cannot open selected backup ZIP")
        var count = 0
        var expanded = 0L
        val staged = File(context.cacheDir, "pocketcraft-import-" + System.nanoTime())
        if (!staged.mkdirs()) throw IOException("Cannot create restore staging area")
        try {
            val src = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open selected backup ZIP")
            src.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++count > MAX_FILES) throw IOException("Too many ZIP entries")
                        val raw = entry.name.replace('\\','/')
                        if (raw.startsWith("/") || raw.contains("\u0000") ||
                            raw.split('/').any { it==".." || it=="." }) {
                            throw IOException("Unsafe path in backup")
                        }
                        val dest = File(staged,raw).canonicalFile
                        if (dest != staged && !dest.path.startsWith(staged.canonicalPath + File.separator))
                            throw IOException("ZIP path escapes staging directory")
                        if (entry.isDirectory) {
                            if (!dest.mkdirs() && !dest.isDirectory) throw IOException("Cannot create restore folder")
                        } else {
                            if (!dest.parentFile.mkdirs() && !dest.parentFile.isDirectory)
                                throw IOException("Cannot create restore parent folder")
                            dest.outputStream().use { out ->
                                val buf=ByteArray(32768)
                                while (true) {
                                    val n=zip.read(buf)
                                    if(n<0)break
                                    expanded+=n
                                    if(expanded>MAX_EXPANDED_BYTES)
                                        throw IOException("Backup exceeds 2 GB safety limit")
                                    out.write(buf,0,n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            if (count==0) throw IOException("ZIP contains no server data")
            if (count != info.files + info.folders)
                throw IOException("Backup changed during verification")
            return ServerRestoreCommit.apply(staged,root)
        } finally {
            staged.deleteRecursively()
        }
    }
}
