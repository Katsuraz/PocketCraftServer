package com.pocketcraft.server

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * Each world has an app-private hosting profile. Minecraft itself still uses
 * server.properties; the selected world's profile is applied when switching.
 * These are server hosting preferences, not direct edits to level.dat.
 */
internal object WorldHostingProfiles {
    val keys = listOf(
        "motd", "difficulty", "gamemode", "pvp",
        "view-distance", "simulation-distance", "spawn-protection"
    )
    private val defaults = mapOf(
        "motd" to "PocketCraft Server",
        "difficulty" to "normal",
        "gamemode" to "survival",
        "pvp" to "true",
        "view-distance" to "5",
        "simulation-distance" to "4",
        "spawn-protection" to "0"
    )
    private val validWorld = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}")

    private fun file(context: Context, world: String): File {
        if (!validWorld.matches(world) || world == "." || world == "..")
            throw IOException("Invalid world name")
        return File(File(context.filesDir, "world-profiles"), "$world.properties")
    }

    fun defaults(): Properties = Properties().also { result ->
        defaults.forEach { (key, value) -> result.setProperty(key, value) }
    }

    /** Read a saved profile or safe defaults, not the currently active server's profile. */
    fun read(context: Context, world: String): Properties {
        val result = defaults()
        val path = file(context, world)
        if (path.isFile) {
            if (path.length() !in 1..16384) throw IOException("World profile is too large or empty")
            val stored = Properties()
            path.inputStream().use { stored.load(it) }
            for (key in keys) stored.getProperty(key)?.let { result.setProperty(key, it) }
        }
        validate(result)?.let { throw IOException("Invalid saved world profile: $it") }
        return result
    }

    /** Active world may have freshly edited server.properties not yet captured on a switch. */
    fun readForEditor(context: Context, world: String): Properties {
        val profile = read(context,world)
        if (world == WorldStore.active(context)) {
            val global = File(WorldStore.root(context),"server.properties")
            val server = Properties()
            if (global.isFile) global.inputStream().use { server.load(it) }
            for (key in keys) server.getProperty(key)?.let { profile.setProperty(key,it) }
            validate(profile)?.let { throw IOException("Invalid active-world setting: $it") }
        }
        return profile
    }

    fun validate(props: Properties): String? {
        val motd = props.getProperty("motd", "")
        if (motd.length > 120 || motd.any { it == '\r' || it == '\n' }) return "MOTD must be at most 120 characters"
        if (props.getProperty("difficulty") !in setOf("peaceful","easy","normal","hard"))
            return "Choose a supported difficulty"
        if (props.getProperty("gamemode") !in setOf("survival","creative","adventure","spectator"))
            return "Choose a supported game mode"
        if (props.getProperty("pvp") !in setOf("true","false"))
            return "PvP must be enabled or disabled"
        for ((key,range) in mapOf("view-distance" to 2..32,
            "simulation-distance" to 2..32,"spawn-protection" to 0..256)) {
            val value = props.getProperty(key)?.toIntOrNull()
            if (value == null || value !in range) return "$key must be between ${range.first} and ${range.last}"
        }
        return null
    }

    fun update(props: Properties, updates: Map<String, String>): Properties {
        val result = Properties()
        for (key in keys) result.setProperty(key, props.getProperty(key, defaults.getValue(key)))
        for ((key,value) in updates) {
            if (key !in keys) throw IOException("Unsupported world setting: $key")
            result.setProperty(key,value)
        }
        validate(result)?.let { throw IOException(it) }
        return result
    }

    private fun writeProperties(target: File, properties: Properties) {
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            properties.store(stream, "PocketCraft world hosting preferences")
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private fun requireStopped() {
        if (ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
            throw IOException("Stop Minecraft before editing world settings")
    }

    /** Called just before changing the active server.properties level-name. */
    fun saveCurrentFromServer(context: Context, world: String, current: Properties) {
        if (!File(WorldStore.root(context), world).isDirectory) return
        val profile = defaults()
        for (key in keys) current.getProperty(key)?.let { profile.setProperty(key,it) }
        validate(profile)?.let { throw IOException("Could not save current world profile: $it") }
        writeProperties(file(context,world),profile)
    }

    fun applyProfile(context: Context, world: String, serverProperties: Properties) {
        val profile = read(context,world)
        for (key in keys) serverProperties.setProperty(key,profile.getProperty(key))
    }

    fun save(context: Context, world: String, profile: Properties) {
        requireStopped()
        val worldDir = File(WorldStore.root(context), world).canonicalFile
        if (worldDir.parentFile != WorldStore.root(context).canonicalFile ||
            !(File(worldDir,"level.dat").isFile ||
                File(worldDir,".pocketcraft-pending-world").isFile))
            throw IOException("Select a managed world")
        validate(profile)?.let { throw IOException(it) }
        val old = file(context,world)
        val previous = if (old.isFile) old.readBytes() else null
        writeProperties(old,profile)
        if (world != WorldStore.active(context)) return
        val global = File(WorldStore.root(context),"server.properties")
        val server = Properties()
        if (global.isFile) global.inputStream().use { server.load(it) }
        applyProfile(context,world,server)
        try { writeProperties(global,server) }
        catch (e: Exception) {
            if (previous == null) old.delete() else old.writeBytes(previous)
            throw e
        }
    }

    fun onDuplicate(context: Context, source: String, dest: String) {
        val original = file(context,source)
        if (original.isFile) {
            val target = file(context,dest)
            if (target.exists()) throw IOException("Duplicated profile already exists")
            target.parentFile?.mkdirs()
            original.copyTo(target,overwrite=false)
        }
    }

    fun onRename(context: Context, source: String, dest: String) {
        val original = file(context,source)
        if (original.isFile) {
            val target = file(context,dest)
            if (target.exists()) throw IOException("Renamed world profile already exists")
            target.parentFile?.mkdirs()
            if (!original.renameTo(target)) throw IOException("Could not rename world profile")
        }
    }

    fun onDelete(context: Context, world: String) {
        file(context,world).delete()
    }
}
