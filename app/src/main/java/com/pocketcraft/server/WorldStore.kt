package com.pocketcraft.server

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** File operations are run on a worker thread by the activity. Never overwrite a live world. */
object WorldStore {
    fun root(c: Context) = File(c.filesDir, "minecraft").apply { mkdirs() }
    fun worlds(c: Context): List<File> =
        root(c).listFiles()?.filter { it.isDirectory && File(it,"level.dat").isFile }?.sortedBy { it.name } ?: emptyList()

    fun active(c: Context): String {
        val props = Properties()
        val f = File(root(c), "server.properties")
        if (f.isFile) f.inputStream().use { props.load(it) }
        return props.getProperty("level-name", "world")
    }
    fun activate(c: Context, world: File) {
        if (ServerService.running) throw IOException("Stop the server before switching worlds")
        if (!world.parentFile.canonicalFile.equals(root(c).canonicalFile) || !File(world,"level.dat").isFile)
            throw IOException("Not a valid Minecraft world")
        val f = File(root(c), "server.properties")
        val props = Properties()
        if (f.exists()) f.inputStream().use { props.load(it) }
        props.setProperty("level-name", world.name)
        if (!props.containsKey("online-mode")) props.setProperty("online-mode", "true")
        f.outputStream().use { props.store(it, "PocketCraft Server") }
    }
    fun backup(c: Context, world: File, uri: Uri) {
        if (ServerService.running) throw IOException("Stop the server before making a consistent backup")
        if (!File(world, "level.dat").exists()) throw IOException("No level.dat found")
        if (world.parentFile?.canonicalFile != root(c).canonicalFile)
            throw IOException("World is outside managed storage")
        val output = c.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write destination")
        output.use { stream ->
            ZipOutputStream(stream).use { zip ->
                world.walkTopDown().filter { it.isFile }.forEach { item ->
                    if (!item.canonicalPath.startsWith(world.canonicalPath + File.separator))
                        throw IOException("World contains an unsafe linked file")
                    // Preserves world folder; re-import supports either root/world or root/level.dat.
                    val path = world.name + "/" + item.relativeTo(world).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(path))
                    item.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
    fun importZip(c: Context, uri: Uri): File {
        if (ServerService.running) throw IOException("Stop the server before importing a world")
        val base = root(c)
        val folder = File(base, "world-import-" + System.currentTimeMillis())
        if (!folder.mkdir()) throw IOException("Failed to create import folder")
        try {
            var files = 0
            var expanded = 0L
            c.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++files > 25000) throw IOException("Archive contains too many files")
                        val raw = entry.name.replace('\\', '/')
                        val sections = raw.split('/').filter { it.isNotEmpty() }
                        if (sections.isEmpty()) { zip.closeEntry(); continue }
                        if (sections.any { it == "." || it == ".." } || raw.startsWith("/"))
                            throw IOException("Unsafe ZIP entry")
                        // If the ZIP contains exactly a 'world/' root, keep only its contents.
                        val relative = if (sections.size > 1 && sections[0] == "world")
                            sections.drop(1).joinToString("/") else sections.joinToString("/")
                        if (relative.isEmpty()) { zip.closeEntry(); continue }
                        val dest = File(folder, relative)
                        if (!dest.canonicalPath.startsWith(folder.canonicalPath + File.separator))
                            throw IOException("Unsafe ZIP destination")
                        if (entry.isDirectory) dest.mkdirs() else {
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { output ->
                                val buf = ByteArray(32768)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    expanded += n
                                    if (expanded > 1024L * 1024L * 1024L)
                                        throw IOException("World exceeds 1 GB extraction limit")
                                    output.write(buf, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw IOException("Cannot open ZIP")
            // Support archives wrapped in an arbitrary single parent directory.
            if (!File(folder, "level.dat").isFile) {
                val children = folder.listFiles() ?: emptyArray()
                if (children.size == 1 && children[0].isDirectory && File(children[0], "level.dat").exists()) {
                    val nested = children[0]
                    nested.listFiles()?.forEach { it.renameTo(File(folder, it.name)) }
                    nested.delete()
                }
            }
            if (!File(folder, "level.dat").isFile) throw IOException("Not a Minecraft Java world (level.dat missing)")
            return folder
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
    }
}
