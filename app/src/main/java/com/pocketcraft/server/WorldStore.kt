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
    private const val PENDING = ".pocketcraft-pending-world"
    private val validName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}")

    fun worlds(c: Context): List<File> =
        root(c).listFiles()?.filter { it.isDirectory &&
            (File(it,"level.dat").isFile || File(it,PENDING).isFile) }
            ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()

    fun isPending(world: File): Boolean = !File(world,"level.dat").isFile && File(world,PENDING).isFile

    private fun requireStopped() {
        if (ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
            throw IOException("Stop Minecraft completely before changing worlds")
    }
    private fun requireValidName(name: String) {
        if (!validName.matches(name) || name=="." || name==".." ||
            name.startsWith("world-import-") || name.startsWith("pocketcraft-"))
            throw IOException("Use 1–48 letters, digits, dots, underscores or dashes. Start with a letter/digit.")
    }
    private fun requireWorld(c: Context, world: File): File {
        val home=root(c).canonicalFile
        val checked=world.canonicalFile
        if (checked.parentFile != home ||
            !(File(checked,"level.dat").isFile || File(checked,PENDING).isFile))
            throw IOException("Not a managed Minecraft world")
        return checked
    }
    private fun assignName(c: Context, name: String) {
        val f=File(root(c),"server.properties")
        val props=Properties()
        if(f.isFile) f.inputStream().use { props.load(it) }
        props.setProperty("level-name",name)
        if(!props.containsKey("online-mode")) props.setProperty("online-mode","true")
        val temp=File(root(c),".server.properties.world-switching")
        try {
            temp.outputStream().use { props.store(it,"PocketCraft Server") }
            if (!temp.renameTo(f)) {
                if (!temp.copyTo(f,overwrite=true).isFile) throw IOException("Could not update active world")
            }
        } finally { temp.delete() }
    }

    /** Reserve an empty world folder; Minecraft generates level.dat on first launch. */
    fun create(c: Context, name: String): File {
        requireStopped()
        requireValidName(name)
        val world=File(root(c),name)
        if(world.exists())throw IOException("A world or folder with this name already exists")
        if(!world.mkdir())throw IOException("Could not create world folder")
        try {
            File(world,PENDING).writeText("PocketCraft new world; Minecraft generates this on start.\n")
            assignName(c,name)
            return world
        }catch(e:Exception){world.deleteRecursively();throw e}
    }

    /** Copy a world safely while offline; the original is never modified. */
    fun duplicate(c: Context, world: File, name: String): File {
        requireStopped()
        val source=requireWorld(c,world)
        requireValidName(name)
        val target=File(root(c),name)
        if(target.exists())throw IOException("A folder with that name already exists")
        if(!target.mkdir())throw IOException("Could not prepare duplicate")
        try {
            var bytes=0L
            var count=0
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                if (++count>50_000)throw IOException("World has too many files")
                if (java.nio.file.Files.isSymbolicLink(file.toPath()) ||
                    !file.canonicalPath.startsWith(source.canonicalPath+File.separator))
                    throw IOException("World contains an unsafe file link")
                bytes += file.length()
                if(bytes>2L*1024*1024*1024)throw IOException("World exceeds 2 GB copy safety limit")
                val dest=File(target,file.relativeTo(source).path)
                if(!dest.canonicalPath.startsWith(target.canonicalPath+File.separator))
                    throw IOException("Invalid path in world")
                dest.parentFile?.mkdirs()
                file.copyTo(dest,overwrite=false)
            }
            return target
        }catch(e:Exception){target.deleteRecursively();throw e}
    }

    fun delete(c: Context, world: File) {
        requireStopped()
        val selected=requireWorld(c,world)
        if(selected.name==active(c)) throw IOException("Select another world before deleting the active one")
        if(!selected.deleteRecursively())throw IOException("Could not delete the world")
    }

    fun rename(c: Context, world: File, name: String): File {
        requireStopped()
        val selected=requireWorld(c,world)
        requireValidName(name)
        val target=File(root(c),name)
        if(target.exists())throw IOException("A folder with that name already exists")
        val wasActive=selected.name==active(c)
        if(!selected.renameTo(target))throw IOException("Could not rename world")
        try {
            if(wasActive)assignName(c,name)
        }catch(e:Exception){target.renameTo(selected);throw e}
        return target
    }

    fun active(c: Context): String {
        val props = Properties()
        val f = File(root(c), "server.properties")
        if (f.isFile) f.inputStream().use { props.load(it) }
        return props.getProperty("level-name", "world")
    }
    fun activate(c: Context, world: File) {
        requireStopped()
        val selected=requireWorld(c,world)
        assignName(c,selected.name)
    }
    fun backup(c: Context, world: File, uri: Uri) {
        requireStopped()
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
        requireStopped()
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
