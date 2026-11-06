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
        "maven.fabricmc.net", // Official Fabric API fallback, validated against Modrinth SHA-512
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
            "/version?loaders=" + encode("[\"fabric\"]") +
            "&game_versions=" + encode("[\"" + current + "\"]") +
            "&include_changelog=false")
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


    data class DependencyChoice(
        val projectId: String, val title: String, val required: Boolean,
        val installed: Boolean, val versionId: String?,
        val kind: String = "required"
    )

    /**
     * Preview dependencies of the EXACT selected release. Incompatible entries
     * are warnings, never selectable optional installations.
     */
    fun previewDependencies(context: Context, slug: String,
                            pinnedVersionId: String? = null,
                            allowVersionMismatch: Boolean = false): List<DependencyChoice> {
        val current = installedVersion(context)
        require(slug.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid Modrinth project" }
        val project = json("https://api.modrinth.com/v2/project/" + encode(slug))
        val id = project.getString("id")
        val versions = if (pinnedVersionId != null) {
            require(pinnedVersionId.matches(Regex("[A-Za-z0-9_-]{1,120}"))) {
                "Invalid Modrinth version ID"
            }
            JSONArray().put(json("https://api.modrinth.com/v2/version/" + encode(pinnedVersionId)))
        } else array("https://api.modrinth.com/v2/project/" + encode(id) +
            "/version?loaders=" + encode("[\"fabric\"]") +
            "&game_versions=" + encode("[\"" + current + "\"]") +
            "&include_changelog=false")
        if (versions.length() == 0) throw IOException("No Fabric $current release found")
        val version = versions.getJSONObject(0)
        if (version.optString("project_id") != id ||
            !(0 until (version.optJSONArray("loaders") ?: JSONArray()).length()).any {
                version.optJSONArray("loaders")?.optString(it) == "fabric"
            } ||
            (!allowVersionMismatch &&
                !(0 until (version.optJSONArray("game_versions") ?: JSONArray()).length()).any {
                    version.optJSONArray("game_versions")?.optString(it) == current
                })) throw IOException("This release is not a matching Fabric $current build. " +
                    "Choose a different version or explicitly confirm the compatibility warning.")
        val deps = version.optJSONArray("dependencies") ?: JSONArray()
        val presentIds = installedModIds(context)
        val found = mutableListOf<DependencyChoice>()
        for (i in 0 until minOf(deps.length(), 60)) {
            val dep = deps.getJSONObject(i)
            val kind = dep.optString("dependency_type")
            if (kind !in setOf("required","optional","incompatible")) continue
            val versionId = dep.optString("version_id").takeIf { it.isNotBlank() }
            val depProjectId = dep.optString("project_id").ifBlank {
                if (versionId == null) "" else
                    json("https://api.modrinth.com/v2/version/" + encode(versionId))
                        .optString("project_id")
            }
            if (depProjectId.isBlank() || !depProjectId.matches(Regex("[A-Za-z0-9_-]{1,120}")))
                continue
            val dependencyProject = json("https://api.modrinth.com/v2/project/" + encode(depProjectId))
            val modId = dependencyProject.optString("slug")
            val installed = modId in presentIds || depProjectId in presentIds
            found.add(DependencyChoice(depProjectId,
                dependencyProject.optString("title",depProjectId),
                kind == "required", installed, versionId, kind))
        }
        return found.distinctBy { it.projectId to it.kind }
    }

    private fun installedModIds(context: Context): Set<String> {
        val mods = File(WorldStore.root(context), "mods")
        return mods.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.endsWith(".jar", true) }
            .mapNotNull { file ->
                runCatching {
                    JarFile(file).use { jar ->
                        val entry = jar.getJarEntry("fabric.mod.json") ?: return@use null
                        if (entry.size > 1024 * 1024) return@use null
                        val meta = JSONObject(jar.getInputStream(entry).bufferedReader().use {
                            it.readText().take(1024 * 1024)
                        })
                        meta.optString("id").takeIf { it.isNotBlank() }
                    }
                }.getOrNull()
            }.toSet()
    }

    /**
     * Resolve required Fabric dependencies and try alternate matching releases
     * if a Modrinth CDN asset disappears (HTTP 404/410). Keep checksums and
     * Fabric JAR verification; never install an unverified or client-only JAR.
     */
    @Synchronized
    fun installWithDependencies(context: Context, slug: String,
                                pinnedVersionId: String? = null,
                                selectedOptional: Set<String> = emptySet(),
                                allowVersionMismatch: Boolean = false,
                                allowPinnedDependencyRecovery: Boolean = false,
                                preferCompatibleDependencyProjects: Set<String> = emptySet(),
                                selectedDependencyVersions: Map<String,String> = emptyMap(),
                                allowSelectedDependencyMismatchProjects: Set<String> = emptySet()): String {
        if (ServerService.running) throw IOException("Stop Minecraft before installing mods")
        val current = installedVersion(context)
        val safeId = Regex("[A-Za-z0-9_-]{1,120}")
        require(preferCompatibleDependencyProjects.all { safeId.matches(it) } &&
            selectedDependencyVersions.all { (id,version) ->
                safeId.matches(id) && safeId.matches(version)
            } && allowSelectedDependencyMismatchProjects.all {
                safeId.matches(it) && it in selectedDependencyVersions
            } && preferCompatibleDependencyProjects.none {
                it in selectedDependencyVersions
            }) { "Invalid or conflicting dependency version choices" }
        require(slug.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid Modrinth project" }
        if (pinnedVersionId != null &&
            !pinnedVersionId.matches(Regex("[A-Za-z0-9_-]{1,120}")))
            throw IOException("Invalid Modrinth version")
        val completed = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val installed = mutableListOf<String>()
        val existingModIds = installedModIds(context)
        fun contains(value: JSONArray, text: String): Boolean =
            (0 until value.length()).any { value.optString(it) == text }
        fun isMissingFile(e: Exception): Boolean =
            e is IOException && (e.message?.contains("HTTP 404") == true ||
                e.message?.contains("HTTP 410") == true)
        fun installProject(projectSlug: String, preferredVersionId: String? = null,
                           depth: Int = 0, recovered: Boolean = false,
                           excludeVersionId: String? = null,
                           explicitlyAllowMismatch: Boolean = false) {
            if (depth > 12 || completed.size > 30) throw IOException("Too many nested dependencies")
            val project = json("https://api.modrinth.com/v2/project/" + encode(projectSlug))
            val id = project.getString("id")
            if (id in completed) return
            if (project.optString("slug") in existingModIds || id in existingModIds) {
                installed.add(project.optString("title", projectSlug) + " (already installed)")
                completed.add(id)
                return
            }
            if (!visiting.add(id)) throw IOException("Dependency cycle: " + project.optString("title"))
            try {
                // Confirmation applies only to the explicitly selected
                // root mod. Dependencies must still be compatible by default.
                val overrideThisProject = preferredVersionId != null &&
                    ((allowVersionMismatch && depth == 0) ||
                        (explicitlyAllowMismatch && depth > 0))
                if (project.optString("server_side") == "unsupported" &&
                    !overrideThisProject)
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
                    // Never retry the exact immutable pin whose download
                    // just failed. Choose a genuinely different release.
                    if (excludeVersionId != null &&
                        version.optString("id") == excludeVersionId) continue
                    val games = version.optJSONArray("game_versions") ?: JSONArray()
                    val loaders = version.optJSONArray("loaders") ?: JSONArray()
                    // Explicit pinned versions may be for another Minecraft
                    // release, but must still be actual Fabric JARs. Nothing
                    // is implicitly forced for auto-resolved dependencies.
                    if ((!contains(games,current) && !overrideThisProject) ||
                        !contains(loaders,"fabric")) continue
                    if (version.optString("environment") in
                        setOf("client_only","singleplayer_only") &&
                        !overrideThisProject) continue
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
                            val kind = d.optString("dependency_type")
                            val depId = d.optString("project_id")
                            if (kind == "incompatible") {
                                if (depId in existingModIds) {
                                    throw IOException("Conflicting installed mod " + depId +
                                        "; disable it and review compatibility before installing " + title)
                                }
                                continue
                            }
                            if (kind != "required" &&
                                !(kind == "optional" && depId in selectedOptional)) continue
                            val depVersion = d.optString("version_id").takeIf { it.isNotBlank() }
                            try {
                                // Resolve project ID independently of a pinned version
                                // whenever possible. A missing version must not prevent
                                // recovery to a matching Fabric version of this project.
                                val dependencyProjectId = if (depId.isNotBlank()) depId
                                else if (depVersion != null) {
                                    json("https://api.modrinth.com/v2/version/" +
                                        encode(depVersion)).getString("project_id")
                                } else throw IOException("A required dependency has no Modrinth project")
                                // Applies to EVERY declared Modrinth dependency,
                                // regardless of parent project or nesting depth.
                                // Exact choices take priority; auto-compatible
                                // intentionally bypasses an unavailable pin.
                                val selectedVersion =
                                    selectedDependencyVersions[dependencyProjectId]
                                val useCompatible =
                                    dependencyProjectId in preferCompatibleDependencyProjects
                                val resolved = ModrinthDependencySelection.choose(
                                    dependencyProjectId,depVersion,
                                    selectedDependencyVersions,
                                    preferCompatibleDependencyProjects
                                )
                                if (selectedVersion != null || useCompatible) {
                                    InstallProgress.emit("Installing selected dependency " +
                                        dependencyProjectId +
                                        (if (selectedVersion != null)
                                            " version " + selectedVersion
                                        else " compatible Fabric " + current))
                                    installProject(dependencyProjectId,resolved,depth+1,
                                        explicitlyAllowMismatch =
                                            dependencyProjectId in
                                                allowSelectedDependencyMismatchProjects)
                                } else {
                                    ModrinthDependencyRecovery.attempt(
                                        pinnedVersionId = depVersion,
                                        userApproved = allowPinnedDependencyRecovery,
                                        first = {
                                            installProject(dependencyProjectId,depVersion,depth+1)
                                        },
                                        compatible = {
                                            installProject(dependencyProjectId,null,depth+1,
                                                recovered=true,excludeVersionId=depVersion)
                                        },
                                        onRecover = {
                                            InstallProgress.emit("Pinned dependency " +
                                                dependencyProjectId +
                                                " unavailable; trying verified compatible Fabric " +
                                                current + " release…")
                                        },
                                        isNestedDependencyError = { it is DependencyInstallFailure }
                                    )
                                }
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
                                val sha512 = jar.optJSONObject("hashes")?.optString("sha512")
                                if (sha512 == null || !sha512.matches(Regex("[0-9a-fA-F]{128}")))
                                    throw IOException("Missing trusted SHA-512 checksum for " + title)
                                installFile(context,jar.getString("url"),jar.getString("filename"),
                                    sha512,title)
                                installed.add(title + " " + version.optString("version_number") +
                                    if (recovered) " (compatible fallback for missing pinned release)" else "")
                                completed.add(id)
                                return
                            } catch (e: IOException) {
                                if (!isMissingFile(e)) throw e
                                missingCount++
                                missingAssetName = jar.optString("filename")
                                // Modrinth's Maven endpoint is a documented
                                // alternative source of the EXACT same release.
                                // A SHA-512 match against the original CDN
                                // metadata is mandatory before any installation.
                                val originalHash = jar.optJSONObject("hashes")
                                    ?.optString("sha512")
                                if (originalHash != null &&
                                    originalHash.matches(Regex("[0-9a-fA-F]{128}"))) {
                                    val mirrors = ModrinthArtifactSources.mavenJarCandidates(
                                        id,project.optString("slug"),
                                        version.optString("id"),
                                        version.optString("version_number"))
                                    for (mirror in mirrors) {
                                        InstallProgress.check()
                                        try {
                                            InstallProgress.emit("Trying verified Modrinth Maven mirror for " +
                                                title + " " + version.optString("version_number"))
                                            installFile(context,mirror,jar.getString("filename"),
                                                originalHash,title)
                                            installed.add(title + " " +
                                                version.optString("version_number") +
                                                (if (recovered) " (compatible dependency fallback)" else "") +
                                                " (verified Maven mirror)")
                                            completed.add(id)
                                            return
                                        } catch (mirrorError: IOException) {
                                            if (mirrorError.message?.contains("checksum",
                                                    ignoreCase = true) == true)
                                                throw IOException("Modrinth Maven returned a different " +
                                                    "file for " + title +
                                                    "; expected SHA-512 did not match",mirrorError)
                                            InstallProgress.emit("Maven mirror unavailable; " +
                                                "trying another verified source…")
                                        }
                                    }
                                }
                                // Modrinth's Fabric API CDN links can return 404
                                // even while the exact artifact is published by Fabric.
                                // Only use the official Fabric artifacts when the
                                // Modrinth-provided SHA-512 can authenticate its bytes.
                                if (id == "P7dR8mSH") {
                                    val number = version.optString("version_number")
                                    val hash = jar.optJSONObject("hashes")?.optString("sha512")
                                        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{128}")) }
                                    if (hash != null &&
                                        number.matches(Regex("[A-Za-z0-9._+-]{1,80}")) &&
                                        number.endsWith("+" + current) &&
                                        jar.optString("filename").startsWith("fabric-api-")) {
                                        val officialJar = "fabric-api-" + number + ".jar"
                                        val officialSources = listOf(
                                            "https://github.com/FabricMC/fabric-api/releases/download/" +
                                                encode(number) + "/" + encode(officialJar),
                                            "https://maven.fabricmc.net/" +
                                                "net/fabricmc/fabric-api/fabric-api/" +
                                                number + "/" + officialJar
                                        )
                                        for (source in officialSources) {
                                            try {
                                                InstallProgress.emit(
                                                    "Trying official Fabric API mirror (SHA-512 verified)")
                                                installFile(context, source, officialJar, hash, title)
                                                installed.add(title + " " +
                                                    version.optString("version_number") +
                                                    (if (recovered) " (compatible dependency fallback)" else "") +
                                                    " (verified official mirror)")
                                                completed.add(id)
                                                return
                                            } catch (fallbackError: IOException) {
                                                if (fallbackError.message?.contains("checksum", true) == true)
                                                    throw IOException(
                                                        "Official Fabric API JAR checksum differs from " +
                                                        "Modrinth; refusing unverified files", fallbackError)
                                                InstallProgress.emit("Mirror unavailable; trying next source…")
                                            }
                                        }
                                    }
                                }
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
                        " release, but its download is unavailable (HTTP 404/410) " +
                        "on Modrinth CDN and verified Maven fallback" +
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
        // The installer never overwrites existing JARs. If an individual
        // dependency fails, roll back newly introduced JARs from this attempt.
        val modsFolder = File(WorldStore.root(context),"mods")
        val before = modsFolder.listFiles().orEmpty().filter { it.isFile }
            .map { it.name }.toSet()
        try {
            installProject(slug, pinnedVersionId)
        } catch (failure: Exception) {
            modsFolder.listFiles().orEmpty().filter {
                it.isFile && it.name.endsWith(".jar",true) && it.name !in before
            }.forEach { it.delete() }
            throw failure
        }
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
