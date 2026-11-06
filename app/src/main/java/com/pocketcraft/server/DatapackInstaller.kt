package com.pocketcraft.server

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Safe, world-scoped Java datapack installer. ZIPs are checked in a temporary file
 * and moved into <active world>/datapacks only after validation. No ZIP extraction.
 * Download methods must run off the Android UI thread.
 */
object DatapackInstaller {
    data class Project(val id: String, val title: String, val description: String)
    private const val MAX_ZIP_BYTES = 64L * 1024 * 1024
    private const val MAX_UNPACKED_BYTES = 512L * 1024 * 1024

    /** Pending worlds accept datapacks before level.dat is generated. */
    internal fun isEligibleWorld(world: File): Boolean =
        File(world, "level.dat").isFile || WorldStore.isPending(world)

    private fun encoded(s: String) = URLEncoder.encode(s, "UTF-8")

    fun activeDirectory(context: Context): File {
        val root = WorldStore.root(context).canonicalFile
        val name = WorldStore.active(context)
        if (!name.matches(Regex("[A-Za-z0-9._-]{1,64}")) || name == "." || name == "..")
            throw IOException("Unsafe world name. Check the active world in Settings.")
        val world = File(root, name).canonicalFile
        if (world.parentFile != root || !isEligibleWorld(world))
            throw IOException("Create or select a managed world first. Datapacks can be installed before its first launch.")
        val folder = File(world, "datapacks").canonicalFile
        if (folder.parentFile != world)
            throw IOException("Datapacks path points outside the active world.")
        return folder
    }

    private fun destination(context: Context): File {
        if (ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
            throw IOException("Stop Minecraft before editing datapacks.")
        val folder = activeDirectory(context)
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Could not create the world datapacks folder.")
        if (!folder.isDirectory) throw IOException("Datapacks path is not a folder.")
        return folder
    }

    fun installed(context: Context): List<File> =
        runCatching { activeDirectory(context).listFiles()?.filter {
            it.isFile && it.name.endsWith(".zip", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() } ?: emptyList() }.getOrDefault(emptyList())

    fun remove(context: Context, filename: String) {
        val folder = destination(context).canonicalFile
        if (!filename.endsWith(".zip", true) || filename.contains('/') ||
            filename.contains('\\') || filename == "..")
            throw IOException("Invalid datapack filename.")
        val candidate = File(folder, filename).canonicalFile
        if (candidate.parentFile != folder || !candidate.isFile)
            throw IOException("Datapack not found in the active world.")
        if (!candidate.delete()) throw IOException("Could not remove datapack.")
    }

    private fun filename(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        if (clean.isBlank() || clean.startsWith(".") ||
            !clean.endsWith(".zip", ignoreCase = true))
            throw IOException("Select a datapack ZIP file, not a .jar or .mrpack.")
        return clean
    }

    private fun verifyArchive(file: File) {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            var count = 0
            var expanded = 0L
            var metadata: java.util.zip.ZipEntry? = null
            while (entries.hasMoreElements()) {
                InstallProgress.check()
                val entry = entries.nextElement()
                count++
                if (count > 10000) throw IOException("Too many files inside datapack ZIP.")
                val name = entry.name
                val segments = name.split('/')
                if (name.startsWith("/") || '\\' in name ||
                    segments.any { it == ".." || it == "." } ||
                    name.contains("\u0000"))
                    throw IOException("Unsafe datapack ZIP entry: " + name.take(60))
                if (entry.size > 0) expanded += entry.size
                if (expanded > MAX_UNPACKED_BYTES)
                    throw IOException("Datapack expands beyond the 512 MB safety limit.")
                if (name == "pack.mcmeta" && !entry.isDirectory) metadata = entry
            }
            val pack = metadata ?: throw IOException(
                "Invalid datapack: pack.mcmeta must be at the ZIP root (not inside another folder).")
            if (pack.size > 128 * 1024)
                throw IOException("Datapack pack.mcmeta is unusually large.")
            val metadataText = zip.getInputStream(pack).use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var length = 0
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    length += n
                    if (length > 128 * 1024) throw IOException("Datapack metadata is too large.")
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
            val root = try { JSONObject(metadataText) }
                catch (_: Exception) { throw IOException("Invalid pack.mcmeta JSON.") }
            if (root.optJSONObject("pack") == null)
                throw IOException("Invalid datapack: missing pack section in pack.mcmeta.")
        }
    }

    private fun installStream(context: Context, input: java.io.InputStream,
                              suggestedName: String, size: Long = -1,
                              expectedSha512: String? = null): String {
        val targetDir = destination(context)
        val name = filename(suggestedName)
        val target = File(targetDir, name).canonicalFile
        if (target.parentFile != targetDir.canonicalFile)
            throw IOException("Unsafe datapack destination.")
        if (target.exists()) throw IOException(
            name + " already exists in this world. Remove it first if you want to replace it.")
        if (size > MAX_ZIP_BYTES) throw IOException("Datapack ZIP is larger than 64 MB.")
        val staged = File(targetDir, ".pocketcraft-datapack-" + System.nanoTime() + ".tmp")
        try {
            var total = 0L
            val hash = MessageDigest.getInstance("SHA-512")
            staged.outputStream().use { output ->
                val buffer = ByteArray(32768)
                while (true) {
                    InstallProgress.check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ZIP_BYTES) throw IOException("Datapack ZIP exceeds 64 MB.")
                    output.write(buffer, 0, n)
                    hash.update(buffer, 0, n)
                    InstallProgress.emit("Copying datapack", total, size)
                }
            }
            if (total == 0L) throw IOException("Empty datapack file.")
            if (expectedSha512 != null) {
                val actual = hash.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha512, true))
                    throw IOException("Datapack download checksum does not match Modrinth.")
            }
            InstallProgress.emit("Verifying datapack contents")
            verifyArchive(staged)
            InstallProgress.check()
            if (ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
                throw IOException("Stop Minecraft before installing datapacks.")
            if (target.exists() || !staged.renameTo(target))
                throw IOException("Cannot install datapack: the destination exists or cannot be written.")
            return "Installed " + name + " into " + WorldStore.active(context) +
                "/datapacks. " + (if (WorldStore.isPending(File(WorldStore.root(context),
                    WorldStore.active(context)))) "Start Minecraft to generate this world with the datapack."
                    else "Restart Minecraft to activate it. Use 'datapack list' in Console to verify.")
        } finally {
            staged.delete()
        }
    }

    fun importZip(context: Context, uri: Uri, name: String): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Android could not read the selected file.")
        return input.use { installStream(context, it, name) }
    }

    // Restrict all API and CDN redirects to known Modrinth HTTPS hosts.
    private fun connect(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            if (url.protocol != "https" || url.host !in setOf(
                    "api.modrinth.com", "cdn.modrinth.com"))
                throw IOException("Untrusted datapack download host: " + url.host)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "PocketCraftServer/0.8.19 (Datapack installer)")
                setRequestProperty("Accept", "application/json, application/octet-stream")
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val target = connection.getHeaderField("Location")
                    ?: throw IOException("Datapack server redirected without a location.")
                connection.disconnect()
                url = URL(url, target)
            } else {
                if (status !in 200..299) {
                    connection.disconnect()
                    throw IOException("Datapack download returned HTTP " + status)
                }
                return connection
            }
        }
        throw IOException("Too many Modrinth download redirects.")
    }

    private fun json(address: String): String {
        val connection = connect(address)
        try {
            return connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.length > 4_000_000) throw IOException("Modrinth response too large.")
                text
            }
        } finally { connection.disconnect() }
    }

    fun search(query: String, gameVersion: String): List<Project> {
        val facets = "[[\"project_type:datapack\"],[\"versions:" +
            gameVersion.replace(Regex("[^A-Za-z0-9._+-]"), "") + "\"]]"
        val url = "https://api.modrinth.com/v2/search?query=" +
            encoded(query.take(80)) + "&facets=" + encoded(facets) + "&limit=15"
        val hits = JSONObject(json(url)).getJSONArray("hits")
        return (0 until hits.length()).map { hits.getJSONObject(it) }
            .filter { it.optString("project_id").matches(Regex("[A-Za-z0-9_-]{1,120}")) }
            .map { Project(it.getString("project_id"),
                it.optString("title"), it.optString("description").take(140)) }
    }

    fun installModrinth(context: Context, project: Project, gameVersion: String): String {
        destination(context) // fail fast before networking if world doesn't exist
        if (!project.id.matches(Regex("[A-Za-z0-9_-]{1,120}")))
            throw IOException("Invalid datapack project ID.")
        val versions = JSONArray(json("https://api.modrinth.com/v2/project/" +
            encoded(project.id) + "/version?game_versions=" +
            encoded("[\"" + gameVersion + "\"]") + "&include_changelog=false"))
        var matched = 0
        var missing = 0
        for (i in 0 until minOf(versions.length(), 20)) {
            InstallProgress.check()
            val release = versions.getJSONObject(i)
            val games = release.optJSONArray("game_versions") ?: JSONArray()
            if (!(0 until games.length()).any { games.optString(it) == gameVersion }) continue
            val platforms = release.optJSONArray("loaders") ?: JSONArray()
            if (platforms.length() > 0 && !(0 until platforms.length()).any {
                    platforms.optString(it) == "datapack" }) continue
            val files = release.optJSONArray("files") ?: JSONArray()
            val zips = (0 until files.length()).map { files.getJSONObject(it) }
                .filter { it.optString("filename").endsWith(".zip", true) &&
                    it.optString("file_type") !in
                        setOf("required-resource-pack", "optional-resource-pack") }
                .sortedByDescending { it.optBoolean("primary") }
            if (zips.isEmpty()) continue
            matched++
            for (zip in zips) {
                InstallProgress.check()
                val checksum = zip.optJSONObject("hashes")?.optString("sha512") ?: ""
                if (!checksum.matches(Regex("[a-fA-F0-9]{128}")))
                    throw IOException("Modrinth did not provide a valid SHA-512 checksum.")
                val connection = try { connect(zip.getString("url")) }
                    catch (e: IOException) {
                        if (e.message?.contains("HTTP 404") == true ||
                            e.message?.contains("HTTP 410") == true) {
                            missing++
                            continue
                        }
                        throw e
                    }
                try {
                    return connection.inputStream.use { stream ->
                        installStream(context, stream, zip.getString("filename"),
                            connection.contentLengthLong, checksum)
                    }
                } finally { connection.disconnect() }
            }
        }
        if (matched == 0) throw IOException(
            "No matching datapack ZIP for Minecraft " + gameVersion +
            ". Choose a supported version or import a verified ZIP.")
        if (missing > 0) throw IOException(
            "Modrinth returned HTTP 404/410 for " + project.title +
            ". No reachable compatible file was found.")
        throw IOException("No installable datapack ZIP found for " + project.title + ".")
    }
}
