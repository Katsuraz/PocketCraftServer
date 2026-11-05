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
import java.util.jar.JarFile

/**
 * Install only approved HTTPS download sources into the phone-hosted Fabric server.
 * Modrinth project/version pages are resolved via the official API; other downloads
 * must be direct .jar/.mrpack URLs. Never run arbitrary files or scripts.
 */
object WebsiteModInstaller {
    private const val MAX_BYTES = 150L * 1024 * 1024
    // Distinguish a required dependency's failed metadata/CDN from the
    // parent mod's missing JAR, so the app reports the real failure.
    private class DependencyInstallFailure(message: String, cause: IOException) :
        IOException(message, cause)

    private fun trusted(host: String): Boolean = host.lowercase() in setOf(
        "api.modrinth.com", "cdn.modrinth.com", "modrinth.com",
        "github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "github-releases.githubusercontent.com"
    )

    private fun connect(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            if (url.protocol != "https" || !trusted(url.host))
                throw IOException("Only approved HTTPS Modrinth or GitHub download links are supported: " + url.host)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "PocketCraftServer/0.8 (Android; Fabric server manager)")
                setRequestProperty("Accept", "application/json, application/octet-stream")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val target = connection.getHeaderField("Location")
                    ?: throw IOException("Download redirected without an address")
                connection.disconnect()
                url = URL(url, target)
            } else {
                if (code !in 200..299) {
                    connection.disconnect()
                    throw IOException("Download failed (HTTP $code)")
                }
                return connection
            }
        }
        throw IOException("Too many download redirects")
    }

    private fun json(address: String): JSONObject {
        val c = connect(address)
        return try {
            val body = c.inputStream.bufferedReader().use { it.readText().take(1_000_000) }
            JSONObject(body)
        } finally { c.disconnect() }
    }

    private fun array(address: String): JSONArray {
        val c = connect(address)
        return try {
            val body = c.inputStream.bufferedReader().use { it.readText().take(2_000_000) }
            JSONArray(body)
        } finally { c.disconnect() }
    }

    private fun download(address: String, output: File, expectedHash: String? = null): Unit {
        val c = connect(address)
        try {
            val size = c.contentLengthLong
            if (size > MAX_BYTES) throw IOException("The download exceeds 150 MB")
            var downloaded = 0L
            val digest = MessageDigest.getInstance("SHA-512")
            c.inputStream.use { input ->
                output.outputStream().use { stream ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        InstallProgress.check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        downloaded += n
                        if (downloaded > MAX_BYTES) throw IOException("Download exceeds 150 MB")
                        stream.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        InstallProgress.emit("Downloading from website", downloaded, size)
                    }
                }
            }
            if (!expectedHash.isNullOrBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedHash, true))
                    throw IOException("File checksum does not match Modrinth")
            }
        } finally { c.disconnect() }
    }

    private fun installedVersion(context: Context): String =
        context.getSharedPreferences("pocketcraft", 0).getString("gameVersion", null)
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Select or install a Fabric Minecraft server version first.")

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun filenamesafe(name: String): String {
        val value = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (value.isBlank() || value.startsWith(".")) throw IOException("Unsafe filename")
        return value.take(140)
    }

    fun install(context: Context, raw: String): String {
        if (ServerService.running) throw IOException("Stop Minecraft before installing mods")
        val link = raw.trim()
        if (link.length !in 12..2048) throw IOException("Paste a complete Modrinth or direct download URL")
        val source = URL(link)
        if (source.protocol != "https" || !trusted(source.host))
            throw IOException("Only approved HTTPS Modrinth and GitHub URLs are supported")
        val current = installedVersion(context)
        val path = source.path.trimEnd('/')
        val segments = path.split('/').filter { it.isNotBlank() }
        if (source.host.equals("modrinth.com", true) &&
            segments.firstOrNull() in listOf("mod", "modpack") && segments.size >= 2) {
            val type = segments[0]
            val slug = segments[1]
            if (!slug.matches(Regex("[A-Za-z0-9_-]{1,120}"))) throw IOException("Invalid Modrinth project URL")
            val project = json("https://api.modrinth.com/v2/project/" + encode(slug))
            val projectType = project.optString("project_type")
            if ((type == "mod" && projectType != "mod") ||
                (type == "modpack" && projectType != "modpack"))
                throw IOException("The Modrinth link does not match its project type")
            if (projectType == "mod" && project.optString("server_side") == "unsupported")
                throw IOException("This Modrinth mod is client-only and cannot be installed on the server")
            // Use the dependency-aware installer for Modrinth mod pages.
            // Explicit version URLs preserve their exact selected release.
            if (projectType == "mod") {
                val pinned = if (segments.size >= 4 && segments[2] == "version")
                    segments[3] else null
                return installWithDependencies(context, slug, pinned)
            }
            val version = if (segments.size >= 4 && segments[2] == "version") {
                json("https://api.modrinth.com/v2/version/" + encode(segments[3]))
            } else {
                val loaders = encode("[\"fabric\"]")
                val mc = encode("[\"" + current + "\"]")
                val versions = array("https://api.modrinth.com/v2/project/" + encode(slug) +
                    "/version?loaders=" + loaders + "&game_versions=" + mc)
                if (versions.length() == 0) throw IOException("No Fabric release compatible with Minecraft $current")
                versions.getJSONObject(0)
            }
            if (!version.optString("project_id").equals(project.getString("id"), true))
                throw IOException("The selected version belongs to another project")
            val supported = version.optJSONArray("game_versions") ?: JSONArray()
            val loaders = version.optJSONArray("loaders") ?: JSONArray()
            if (!(0 until supported.length()).any { supported.optString(it) == current } ||
                !(0 until loaders.length()).any { loaders.optString(it) == "fabric" })
                throw IOException("Selected version is not compatible with Fabric Minecraft $current")
            val files = version.getJSONArray("files")
            val extension = if (projectType == "modpack") ".mrpack" else ".jar"
            val selected = (0 until files.length()).map { files.getJSONObject(it) }
                .filter { it.optString("filename").endsWith(extension, ignoreCase = true) }
                .sortedByDescending { it.optBoolean("primary") }.firstOrNull()
                ?: throw IOException("No $extension download available for this release")
            return installFile(context, selected.getString("url"),
                selected.optString("filename"), selected.optJSONObject("hashes")?.optString("sha512"),
                project.optString("title"))
        }
        val extension = when {
            path.endsWith(".mrpack", true) -> ".mrpack"
            path.endsWith(".jar", true) -> ".jar"
            else -> throw IOException("Use a Modrinth project page, or a direct .jar / .mrpack download link. GitHub release pages are not direct downloads.")
        }
        if (source.host == "github.com" && (segments.size < 6 || segments[2] != "releases" || segments[3] != "download"))
            throw IOException("Paste a direct GitHub release asset link ending in $extension")
        return installFile(context, link, filenamesafe(path), null, "Website download")
    }


    /**
     * Resolve required Fabric dependencies and try alternate matching releases
     * if a Modrinth CDN asset disappears (HTTP 404/410). Keep checksums and
     * Fabric JAR verification; never install an unverified or client-only JAR.
     */
    fun installWithDependencies(context: Context, slug: String, pinnedVersionId: String? = null): String {
        if (ServerService.running) throw IOException("Stop Minecraft before installing mods")
        val current = installedVersion(context)
        require(slug.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid Modrinth project" }
        if (pinnedVersionId != null &&
            !pinnedVersionId.matches(Regex("[A-Za-z0-9_-]{1,120}")))
            throw IOException("Invalid Modrinth version")
        val completed = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val installed = mutableListOf<String>()
        fun contains(value: JSONArray, text: String): Boolean =
            (0 until value.length()).any { value.optString(it) == text }
        fun isMissingFile(e: Exception): Boolean =
            e is IOException && (e.message?.contains("HTTP 404") == true ||
                e.message?.contains("HTTP 410") == true)
        fun installProject(projectSlug: String, preferredVersionId: String? = null, depth: Int = 0) {
            if (depth > 12 || completed.size > 30) throw IOException("Too many nested dependencies")
            val project = json("https://api.modrinth.com/v2/project/" + encode(projectSlug))
            val id = project.getString("id")
            if (id in completed) return
            if (!visiting.add(id)) throw IOException("Dependency cycle: " + project.optString("title"))
            try {
                if (project.optString("server_side") == "unsupported")
                    throw IOException(project.optString("title") + " is client-only")
                val versions = if (preferredVersionId != null) {
                    JSONArray().put(json("https://api.modrinth.com/v2/version/" + encode(preferredVersionId)))
                } else array("https://api.modrinth.com/v2/project/" + encode(id) +
                    "/version?loaders=" + encode("[\"fabric\"]") +
                    "&game_versions=" + encode("[\"" + current + "\"]") +
                    "&include_changelog=false")
                val title = project.optString("title",projectSlug)
                var compatibleCount = 0
                var missingCount = 0
                var dependencyIssue: String? = null
                var missingAssetName: String? = null
                for (i in 0 until minOf(versions.length(), 20)) {
                    InstallProgress.check()
                    val version = versions.getJSONObject(i)
                    if (version.optString("project_id") != id) continue
                    val games = version.optJSONArray("game_versions") ?: JSONArray()
                    val loaders = version.optJSONArray("loaders") ?: JSONArray()
                    if (!contains(games,current) || !contains(loaders,"fabric")) continue
                    if (version.optString("environment") in setOf("client_only","singleplayer_only")) continue
                    val files = version.optJSONArray("files") ?: JSONArray()
                    val jars = (0 until files.length()).map { files.getJSONObject(it) }
                        .filter {
                            val filename = it.optString("filename")
                            filename.endsWith(".jar",true) &&
                                it.optString("file_type") !in setOf("sources-jar","dev-jar","javadoc-jar")
                        }
                        .sortedWith(compareByDescending<JSONObject> {
                            it.optString("filename").contains("fabric",true)
                        }.thenByDescending { it.optBoolean("primary") })
                    if (jars.isEmpty()) continue
                    compatibleCount++
                    try {
                        val deps = version.optJSONArray("dependencies") ?: JSONArray()
                        for (j in 0 until deps.length()) {
                            val d = deps.getJSONObject(j)
                            if (d.optString("dependency_type") != "required") continue
                            val depId = d.optString("project_id")
                            val depVersion = d.optString("version_id").takeIf { it.isNotBlank() }
                            try {
                                if (depId.isNotBlank()) installProject(depId,depVersion,depth+1)
                                else if (depVersion != null) {
                                    val dep = json("https://api.modrinth.com/v2/version/" + encode(depVersion))
                                    installProject(dep.getString("project_id"),depVersion,depth+1)
                                } else throw IOException("A required dependency has no Modrinth project")
                            } catch (e: IOException) {
                                throw DependencyInstallFailure(
                                    title + " needs a dependency (" +
                                    depId.ifBlank { depVersion ?: "unknown" } +
                                    ") that could not be installed: " + (e.message ?: "network failure"), e)
                            }
                        }
                        for (jar in jars) {
                            InstallProgress.check()
                            try {
                                installFile(context,jar.getString("url"),jar.getString("filename"),
                                    jar.optJSONObject("hashes")?.optString("sha512"),title)
                                installed.add(title)
                                completed.add(id)
                                return
                            } catch (e: IOException) {
                                if (!isMissingFile(e)) throw e
                                missingCount++
                                missingAssetName = jar.optString("filename")
                                InstallProgress.emit("Unavailable " + title +
                                    " file; trying another compatible release…")
                            }
                        }
                    } catch (e: IOException) {
                        if (e is DependencyInstallFailure) {
                            dependencyIssue = e.message
                            InstallProgress.emit("A dependency for " + title +
                                " failed; trying another compatible release…")
                        } else {
                            // Only a failed parent JAR transfer should produce the
                            // "Modrinth download missing" message, never metadata.
                            throw e
                        }
                    }
                    // Explicit version pins cannot silently fall back to other releases.
                    if (preferredVersionId != null) break
                }
                if (compatibleCount == 0)
                    throw IOException("No Fabric " + current + " server release for " + title)
                if (missingCount > 0)
                    throw IOException(title + " has a Fabric " + current +
                        " version, but its JAR download returned HTTP 404/410" +
                        (missingAssetName?.let { " (" + it + ")" } ?: "") +
                        ". Try the official Fabric JAR from the author's website, then " +
                        "Mods > IMPORT MOD. Do not use the Bukkit/Paper plugin JAR." +
                        (dependencyIssue?.let { "\nAnother issue: " + it } ?: ""))
                if (dependencyIssue != null) throw IOException(dependencyIssue)
                throw IOException("No installable Fabric " + current + " JAR for " + title)
            } finally {
                visiting.remove(id)
            }
        }
        installProject(slug, pinnedVersionId)
        return "Installed " + installed.joinToString(", ") +
            ". Restart Minecraft to load mods."
    }

    private fun installFile(context: Context, url: String, name: String, sha512: String?, title: String): String {
        val filename = filenamesafe(name)
        val pack = filename.endsWith(".mrpack", true)
        if (!pack && !filename.endsWith(".jar", true)) throw IOException("Only .jar or .mrpack downloads are supported")
        val cache = File(context.cacheDir, "web-mod-" + System.nanoTime() +
            if (pack) ".mrpack" else ".jar")
        try {
            download(url, cache, sha512)
            if (pack) return ModpackImporter.install(context, Uri.fromFile(cache)).description()
            JarFile(cache).use { jar ->
                if (jar.getJarEntry("fabric.mod.json") == null)
                    throw IOException("This JAR has no fabric.mod.json and may not be a Fabric mod")
            }
            val target = File(WorldStore.root(context), "mods/$filename")
            target.parentFile?.mkdirs()
            if (target.exists()) return "$filename is already installed; no files were overwritten."
            if (!cache.renameTo(target)) cache.copyTo(target, overwrite = false)
            return "$title\nInstalled $filename for Fabric Minecraft " + installedVersion(context) + ". Restart Minecraft to load it."
        } finally { cache.delete() }
    }
}
