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

/**
 * Read-only Modrinth v2 catalog API for Fabric server mods and .mrpack packs.
 * The final selected version, not the search hit, determines compatibility.
 */
internal object ModrinthCatalog {
    const val PAGE_SIZE = 20
    val sorts = listOf("relevance", "downloads", "updated", "newest", "follows")
    val categories = listOf("all", "optimization", "worldgen", "utility", "adventure",
        "technology", "decoration", "food", "storage", "equipment", "library", "management")
    private const val API = "https://api.modrinth.com/v2"
    private const val MAX_JSON = 2_000_000
    private const val PACK_LIMIT = 150L * 1024 * 1024
    private val identifier = Regex("[A-Za-z0-9_-]{1,120}")
    private val minecraftVersion = Regex("[A-Za-z0-9._+-]{1,45}")

    data class Hit(
        val id: String,
        val slug: String,
        val title: String,
        val description: String,
        val author: String,
        val downloads: Long,
        val iconUrl: String?,
        val serverHint: String
    )
    data class Page(val hits: List<Hit>, val totalHits: Int, val offset: Int)
    data class Version(
        val id: String,
        val projectId: String,
        val title: String,
        val number: String,
        val releaseType: String,
        val published: String,
        val downloads: Long,
        val requiredCount: Int,
        val optionalCount: Int,
        val incompatibleCount: Int,
        val filename: String,
        val url: String,
        val sha512: String?,
        val gameVersions: List<String>,
        val environment: String
    )
    fun validIdentifier(value: String): Boolean = identifier.matches(value)
    fun isServerEligible(environment: String): Boolean =
        environment !in setOf("client_only", "singleplayer_only")

    fun searchUrl(type: String, query: String, gameVersion: String,
                  category: String, sort: String, offset: Int): String {
        require(type in setOf("mod", "modpack")) { "Only Minecraft mods and modpacks are supported" }
        require(gameVersion.isNotBlank() && minecraftVersion.matches(gameVersion)) {
            "Select a valid Minecraft version first"
        }
        require(category in categories) { "Unknown Modrinth category" }
        require(sort in sorts) { "Unknown Modrinth sort" }
        require(offset in 0..2000 && offset % PAGE_SIZE == 0) { "Invalid search page" }
        val facets = mutableListOf("project_type:" + type,
            "categories:fabric", "versions:" + gameVersion)
        if (category != "all") facets.add("categories:" + category)
        val facetJson = facets.joinToString(",", "[", "]") { "[\"" + it + "\"]" }
        return API + "/search?query=" + encode(query.trim().take(100)) +
            "&facets=" + encode(facetJson) +
            "&index=" + encode(sort) + "&offset=" + offset + "&limit=" + PAGE_SIZE
    }
    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun apiJson(url: String): String {
        val location = URL(url)
        if (location.protocol != "https" || location.host != "api.modrinth.com")
            throw IOException("Untrusted Modrinth API address")
        val conn = location.openConnection() as HttpURLConnection
        conn.connectTimeout = 18000
        conn.readTimeout = 45000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "PocketCraftServer/0.8.41 (Android; Fabric server manager)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299)
                throw IOException("Modrinth API HTTP " + conn.responseCode)
            conn.inputStream.use { stream ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_JSON) throw IOException("Modrinth response too large")
                    bytes.write(buffer, 0, read)
                }
                return bytes.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    }

    fun search(type: String, query: String, gameVersion: String, category: String,
               sort: String, offset: Int): Page {
        val root = JSONObject(apiJson(searchUrl(type,query,gameVersion,category,sort,offset)))
        val hits = root.optJSONArray("hits") ?: JSONArray()
        return Page((0 until hits.length()).mapNotNull { i ->
            val hit = hits.optJSONObject(i) ?: return@mapNotNull null
            val id = hit.optString("project_id")
            val slug = hit.optString("slug")
            if (!validIdentifier(id) || !validIdentifier(slug)) return@mapNotNull null
            val environment = hit.optJSONArray("environment")
            val serverHint = when {
                environment != null && (0 until environment.length()).all { index ->
                    environment.optString(index) in setOf("client_only", "singleplayer_only")
                } && environment.length() > 0 -> "Client-only project"
                hit.optString("server_side") == "unsupported" -> "Client-only project"
                else -> "Check selected release"
            }
            Hit(id, slug, hit.optString("title", slug),
                hit.optString("description").take(250), hit.optString("author"),
                hit.optLong("downloads", 0L), hit.optString("icon_url").takeIf {
                    it.startsWith("https://cdn.modrinth.com/")
                }, serverHint)
        }, root.optInt("total_hits",hits.length()).coerceAtLeast(0), offset)
    }

    private fun strings(values: JSONArray?): List<String> =
        if (values == null) emptyList() else (0 until values.length()).map { values.optString(it) }

    fun parseVersion(projectId: String, type: String, gameVersion: String, raw: JSONObject): Version? {
        if (raw.optString("project_id") != projectId) return null
        if (type !in setOf("mod", "modpack")) return null
        if (gameVersion !in strings(raw.optJSONArray("game_versions")) ||
            "fabric" !in strings(raw.optJSONArray("loaders"))) return null
        val environment = raw.optString("environment")
        if (type == "mod" && !isServerEligible(environment)) return null
        val extension = if (type == "mod") ".jar" else ".mrpack"
        val files = raw.optJSONArray("files") ?: return null
        val selected = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .filter {
                it.optString("filename").endsWith(extension, true) &&
                    it.optString("file_type") !in setOf("sources-jar","dev-jar","javadoc-jar")
            }.sortedWith(compareByDescending<JSONObject> { it.optBoolean("primary") }
                .thenBy { it.optString("filename") }).firstOrNull() ?: return null
        val url = selected.optString("url")
        if (!url.startsWith("https://cdn.modrinth.com/")) return null
        val deps = raw.optJSONArray("dependencies") ?: JSONArray()
        fun count(type: String) = (0 until deps.length()).count {
            deps.optJSONObject(it)?.optString("dependency_type") == type
        }
        val sha512 = selected.optJSONObject("hashes")?.optString("sha512")?.takeIf {
            it.matches(Regex("[0-9a-fA-F]{128}"))
        }
        return Version(raw.optString("id"), projectId,raw.optString("name"),
            raw.optString("version_number"),raw.optString("version_type"),
            raw.optString("date_published").take(10),raw.optLong("downloads"),
            count("required"),count("optional"),count("incompatible"),
            selected.optString("filename"),url,sha512,
            strings(raw.optJSONArray("game_versions")),environment)
    }

    fun versions(projectId: String, type: String, gameVersion: String): List<Version> {
        require(validIdentifier(projectId)) { "Invalid Modrinth project ID" }
        require(type in setOf("mod","modpack")) { "Invalid project type" }
        require(minecraftVersion.matches(gameVersion)) { "Invalid Minecraft version" }
        val url = API + "/project/" + encode(projectId) + "/version?loaders=" +
            encode("[\"fabric\"]") + "&game_versions=" + encode("[\"" + gameVersion + "\"]") +
            "&include_changelog=false"
        val data = JSONArray(apiJson(url))
        return (0 until minOf(data.length(),100)).mapNotNull { idx ->
            parseVersion(projectId,type,gameVersion,data.optJSONObject(idx) ?: return@mapNotNull null)
        }
    }

    /** Only a pinned, matching .mrpack from Modrinth's API can be imported. */
    fun downloadPack(context: Context, version: Version): Uri {
        if (ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
            throw IOException("Stop Minecraft before installing a modpack")
        if (!version.filename.endsWith(".mrpack",true) || version.sha512 == null)
            throw IOException("This pack version has no verifiable .mrpack SHA-512")
        val source = URL(version.url)
        if (source.protocol != "https" || source.host != "cdn.modrinth.com")
            throw IOException("Untrusted Modrinth pack file")
        val target = File(context.cacheDir, "modrinth-selected-" + System.nanoTime() + ".mrpack")
        val conn = source.openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode !in 200..299) throw IOException("Pack download HTTP " + conn.responseCode)
            if (conn.contentLengthLong > PACK_LIMIT) throw IOException("Pack archive exceeds 150 MB")
            val digest = MessageDigest.getInstance("SHA-512")
            var copied = 0L
            conn.inputStream.use { input ->
                target.outputStream().buffered().use { out ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        InstallProgress.check()
                        val size = input.read(buffer)
                        if (size < 0) break
                        copied += size
                        if (copied > PACK_LIMIT) throw IOException("Pack archive exceeds 150 MB")
                        digest.update(buffer,0,size)
                        out.write(buffer,0,size)
                        InstallProgress.emit("Downloading " + version.number, copied, conn.contentLengthLong)
                    }
                }
            }
            if (!digest.digest().joinToString("") { "%02x".format(it) }
                .equals(version.sha512,true)) throw IOException("Modrinth pack SHA-512 mismatch")
            return Uri.fromFile(target)
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally { conn.disconnect() }
    }
}
