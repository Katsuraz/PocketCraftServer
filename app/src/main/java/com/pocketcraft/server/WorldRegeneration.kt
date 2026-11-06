package com.pocketcraft.server

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties

/**
 * Regenerate by making a NEW empty world, NOT by cloning level.dat or deleting
 * only region files. Keeps original seed, installed datapacks and hosting profile.
 * Inventories, advancements, entities and generated chunks reset.
 */
internal object WorldRegeneration {
    data class Preview(val name: String, val seed: Long, val datapacks: Int)
    data class Result(val message: String)

    private const val MAX_DATAPACK_BYTES = 700L * 1024 * 1024
    private const val MAX_DATAPACK_FILES = 10_000
    private val names = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}")

    private fun stopped() {
        if (ServerService.running || ServerService.status in setOf("STARTING", "STOPPING"))
            throw IOException("Stop Minecraft completely before regenerating a world.")
    }

    private fun source(c: Context, selected: File): File {
        stopped()
        val parent = WorldStore.root(c).canonicalFile
        val world = selected.canonicalFile
        if (world.parentFile != parent || !File(world, "level.dat").isFile)
            throw IOException("Select a generated PocketCraft world first.")
        return world
    }

    fun preview(c: Context, selected: File): Preview {
        val world = source(c, selected)
        val seed = WorldSeedReader.readSeed(world)
        val packs = File(world, "datapacks")
        if (packs.exists() && !packs.isDirectory)
            throw IOException("The world datapacks path is not a directory.")
        return Preview(world.name, seed, packs.listFiles()?.size ?: 0)
    }

    private fun profile(c: Context, world: File, seed: Long): Properties =
        WorldHostingProfiles.update(
            WorldHostingProfiles.readForEditor(c, world.name),
            mapOf("level-seed" to seed.toString())
        )

    /** Copy both ZIP and folder-based datapacks without changing contents. */
    internal fun copyDatapacks(source: File, destination: File): Int {
        val original = File(source, "datapacks")
        if (!original.exists()) return 0
        if (!original.isDirectory || Files.isSymbolicLink(original.toPath()))
            throw IOException("Unsafe original datapacks folder.")
        val target = File(destination, "datapacks")
        if (!target.mkdirs() && !target.isDirectory)
            throw IOException("Could not create datapacks folder.")
        val root = original.canonicalFile
        val outRoot = target.canonicalFile
        var count = 0
        var totalBytes = 0L
        for (item in original.walkTopDown()) {
            if (item == original) continue
            if (Files.isSymbolicLink(item.toPath()) ||
                !item.canonicalPath.startsWith(root.path + File.separator))
                throw IOException("Unsafe linked file in world datapacks.")
            val relative = item.relativeTo(original).invariantSeparatorsPath
            val dest = File(target, relative).canonicalFile
            if (!dest.path.startsWith(outRoot.path + File.separator))
                throw IOException("Datapack path escapes destination.")
            if (item.isDirectory) {
                if (!dest.mkdirs() && !dest.isDirectory)
                    throw IOException("Cannot create datapack subfolder.")
            } else if (item.isFile) {
                if (++count > MAX_DATAPACK_FILES) throw IOException("Too many datapack files to copy.")
                totalBytes += item.length()
                if (totalBytes > MAX_DATAPACK_BYTES)
                    throw IOException("Datapacks exceed 700 MB; no world was regenerated.")
                dest.parentFile?.mkdirs()
                item.copyTo(dest, overwrite = false)
            } else throw IOException("Unknown datapack file type.")
        }
        return count
    }

    fun regenerateAsNew(c: Context, selected: File, name: String): Result {
        val original = source(c, selected)
        if (!names.matches(name) || name == "." || name == "..")
            throw IOException("Choose a valid new world name.")
        if (File(WorldStore.root(c), name).exists())
            throw IOException("A world named " + name + " already exists.")
        val p = preview(c, original)
        val settings = profile(c, original, p.seed)
        val before = WorldStore.active(c)
        var created: File? = null
        try {
            created = WorldStore.create(c, name)
            val copied = copyDatapacks(original, created)
            WorldHostingProfiles.save(c, name, settings)
            return Result("Created a fresh world '" + name + "' with seed " + p.seed +
                " and " + copied + " datapack file(s). Your original world is untouched. " +
                "Start Minecraft to generate the new world.")
        } catch (e: Exception) {
            created?.deleteRecursively()
            val previous = WorldStore.worlds(c).firstOrNull { it.name == before } ?: original
            val restore = runCatching { WorldStore.activate(c, previous) }
            if (restore.isFailure) throw IOException(
                "New-world creation failed and the old world assignment could not be restored. " +
                    "Original files are intact: " + e.message, e)
            throw IOException("Could not create fresh world; original is preserved: " + e.message, e)
        }
    }

    /**
     * Verify and save a PocketCraft snapshot before swapping any world folders.
     * Staged old world is kept until the replacement and profile are both saved.
     */
    fun regenerateSameName(c: Context, selected: File): Result {
        val original = source(c, selected)
        val p = preview(c, original)
        val settings = profile(c, original, p.seed)
        val snapshot = WorldSnapshots.create(c, original)
        val base = WorldStore.root(c)
        val id = System.nanoTime().toString(36)
        val staged = File(base, ".pocketcraft-regenerate-new-" + id)
        val previous = File(base, ".pocketcraft-regenerate-old-" + id)
        if (staged.exists() || previous.exists() || !staged.mkdir())
            throw IOException("Cannot prepare safe regeneration. Snapshot: " + snapshot.name)
        var oldMoved = false
        var newMoved = false
        try {
            File(staged, ".pocketcraft-pending-world")
                .writeText("PocketCraft regenerated world; generate on first start.\n")
            val copied = copyDatapacks(original, staged)
            stopped()
            if (!original.renameTo(previous)) throw IOException("Cannot preserve old world folder.")
            oldMoved = true
            if (!staged.renameTo(original)) throw IOException("Cannot activate fresh world folder.")
            newMoved = true
            WorldHostingProfiles.save(c, original.name, settings)
            val cleaned = previous.deleteRecursively()
            return Result("World '" + p.name + "' reset to seed " + p.seed + ", with " +
                copied + " datapack file(s). Saved backup: " + snapshot.name +
                ". No chunks or player progress copied. " +
                (if (!cleaned) "Original files additionally remain in " + previous.name + ". " else "") +
                "Start Minecraft to generate this world.")
        } catch (e: Exception) {
            if (newMoved && original.exists() && !original.deleteRecursively())
                throw IOException("Rollback failed. Old world is in " + previous.name +
                    " and snapshot: " + snapshot.name + ". " + e.message, e)
            if (oldMoved && previous.exists() && !previous.renameTo(original))
                throw IOException("Could not restore original world folder. Old world remains in " +
                    previous.name + ", snapshot: " + snapshot.name + ". " + e.message, e)
            throw IOException("Regeneration failed and original world restored. Snapshot: " +
                snapshot.name + ". " + e.message, e)
        } finally {
            if (staged.exists()) staged.deleteRecursively()
        }
    }
}
