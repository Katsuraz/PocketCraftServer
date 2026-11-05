package com.pocketcraft.server

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Verified local world snapshots (not a substitute for off-device backups).
 * Offline-only, capped in size/count, no overwrite of existing world files.
 */
internal object WorldSnapshots {
    private const val MAX_FILES = 50_000
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val KEEP_PER_WORLD = 5
    private fun folder(c: Context) = File(c.filesDir,"world-snapshots").apply { mkdirs() }
    private fun requireStopped() {
        if(ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
            throw IOException("Stop the Minecraft server before backing up or restoring worlds")
    }
    private fun validWorld(c: Context, world: File): File {
        val root=WorldStore.root(c).canonicalFile
        val selected=world.canonicalFile
        if(selected.parentFile != root || !File(selected,"level.dat").isFile)
            throw IOException("Select an existing generated world with level.dat")
        return selected
    }
    private fun safeSnapshot(c: Context, file: File): File {
        val root=folder(c).canonicalFile
        val selected=file.canonicalFile
        if(selected.parentFile != root || !selected.isFile ||
            !selected.name.endsWith(".zip",ignoreCase=true))
            throw IOException("Invalid PocketCraft snapshot")
        return selected
    }
    fun list(c: Context, world: File? = null): List<File> {
        val prefix=world?.let { it.name+"--" }
        return folder(c).listFiles()?.filter {
            it.isFile && it.name.endsWith(".zip") &&
                (prefix==null || it.name.startsWith(prefix))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    fun create(c: Context, world: File): File {
        requireStopped()
        val source=validWorld(c,world)
        val total=list(c,source).size
        val timestamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(Date())
        val destination=File(folder(c),source.name+"--"+timestamp+"-"+System.nanoTime().toString(36)+".zip")
        val staged=File(folder(c),destination.name+".part")
        var fileCount=0
        var bytes=0L
        try {
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                source.walkTopDown().filter { it.isFile }.forEach { file ->
                    if(++fileCount>MAX_FILES)throw IOException("World contains too many files for a snapshot")
                    if(java.nio.file.Files.isSymbolicLink(file.toPath()) ||
                        !file.canonicalPath.startsWith(source.canonicalPath+File.separator))
                        throw IOException("Unsafe symlink in world")
                    bytes+=file.length()
                    if(bytes>MAX_BYTES)throw IOException("World exceeds 2 GB snapshot limit")
                    val relative=file.relativeTo(source).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().buffered().use { it.copyTo(zip,32*1024) }
                    zip.closeEntry()
                }
            }
            if(fileCount<1 || staged.length()==0L)throw IOException("Snapshot was empty")
            if(!staged.renameTo(destination))throw IOException("Could not finish snapshot")
            // Retention runs only *after* successfully creating a new snapshot.
            if(total>=KEEP_PER_WORLD)list(c,source).drop(KEEP_PER_WORLD).forEach {it.delete()}
            return destination
        }catch(e:Exception){staged.delete();throw e}
    }

    /** Restores into a new world name, never over an existing world. */
    fun restore(c: Context, snapshot: File, newName: String): File {
        requireStopped()
        val archive=safeSnapshot(c,snapshot)
        if(!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}").matches(newName))
            throw IOException("Choose a valid 1–48 character world name")
        val target=File(WorldStore.root(c),newName)
        if(target.exists())throw IOException("That world name is already taken")
        if(!target.mkdir())throw IOException("Cannot prepare restored world")
        var count=0
        var expanded=0L
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    if(++count>MAX_FILES)throw IOException("Too many snapshot entries")
                    val path=entry.name.replace('\\','/')
                    val parts=path.split('/')
                    if(path.startsWith("/") || '\u0000' in path ||
                        parts.any { it=="." || it==".." || it.isBlank() })
                        throw IOException("Unsafe backup entry")
                    val dest=File(target,path).canonicalFile
                    if(!dest.path.startsWith(target.canonicalPath+File.separator))
                        throw IOException("Backup path escapes world")
                    if(entry.isDirectory) {
                        if(!dest.mkdirs() && !dest.isDirectory) throw IOException("Cannot create folder")
                    } else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().buffered().use { output ->
                            val buf=ByteArray(32*1024)
                            while(true) {
                                val n=zip.read(buf)
                                if(n<0)break
                                expanded+=n
                                if(expanded>MAX_BYTES)throw IOException("Snapshot exceeds 2 GB restore limit")
                                output.write(buf,0,n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            if(!File(target,"level.dat").isFile)throw IOException("Snapshot has no level.dat")
            return target
        }catch(e:Exception){target.deleteRecursively();throw e}
    }
    fun delete(c: Context, snapshot: File) {
        requireStopped()
        if(!safeSnapshot(c,snapshot).delete())throw IOException("Could not delete snapshot")
    }
}
