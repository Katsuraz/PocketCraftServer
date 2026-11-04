package com.pocketcraft.server

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Small, bounded automatic recovery snapshots before destructive world actions.
 * Snapshots stay in app-private Minecraft/pocketcraft-backups and must be
 * exported through Server Files before uninstalling the app.
 */
internal object WorldSafety {
    private const val MAX_INPUT_BYTES = 512L * 1024L * 1024L
    private const val MAX_FILE_COUNT = 40_000

    fun snapshot(context: Context, world: File): File {
        if (ServerService.running) throw IOException("Stop Minecraft before backing up the world")
        val root=WorldStore.root(context).canonicalFile
        val source=world.canonicalFile
        if (source.parentFile != root || !File(source,"level.dat").isFile) {
            throw IOException("Not a managed Minecraft world")
        }
        val archiveDir=File(root,"pocketcraft-backups")
        if (!archiveDir.isDirectory && !archiveDir.mkdirs()) {
            throw IOException("Cannot create recovery backups folder")
        }
        val entries=source.walkTopDown().filter{it.isFile}.toList()
        if(entries.size>MAX_FILE_COUNT)throw IOException("World has too many files for automatic backup")
        var bytes=0L
        for(file in entries) {
            if (file.canonicalPath != file.absolutePath ||
                !file.canonicalPath.startsWith(source.canonicalPath+File.separator)) {
                throw IOException("World contains linked or unsafe files")
            }
            bytes+=file.length()
            if(bytes>MAX_INPUT_BYTES)throw IOException(
                "World exceeds 512 MB automatic backup limit. Export it manually before deleting.")
        }
        if(archiveDir.usableSpace < bytes+64L*1024L*1024L) {
            throw IOException("Not enough free space for a safe world backup")
        }
        val safeName=source.name.replace(Regex("[^A-Za-z0-9._-]"),"_").take(70)
        val archive=File(archiveDir,safeName+"-"+System.currentTimeMillis()+".zip")
        val temporary=File(archiveDir,"."+archive.name+".part")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                for (file in entries) {
                    val entry=ZipEntry(source.name+"/"+file.relativeTo(source).invariantSeparatorsPath)
                    zip.putNextEntry(entry)
                    file.inputStream().use{it.copyTo(zip,32768)}
                    zip.closeEntry()
                }
            }
            if(!temporary.renameTo(archive))throw IOException("Could not finalize recovery snapshot")
            // Only remove older snapshots after a complete, verified new ZIP exists.
            archiveDir.listFiles()?.filter{it.name.startsWith(safeName+"-") && it.name.endsWith(".zip")}
                ?.sortedByDescending{it.lastModified()}?.drop(3)?.forEach{it.delete()}
            return archive
        } finally {
            temporary.delete()
        }
    }
}
