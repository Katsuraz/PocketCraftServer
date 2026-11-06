package com.pocketcraft.server

import java.net.URLEncoder

/**
 * Recover missing Modrinth CDN assets from Modrinth's own documented Maven
 * repository. Each returned candidate is still verified against the ORIGINAL
 * SHA-512 from the exact Modrinth version; no unverified mirrors or version
 * substitutions are allowed.
 *
 * https://support.modrinth.com/en/articles/8801191-modrinth-maven
 */
internal object ModrinthArtifactSources {
    private val safeToken = Regex("[A-Za-z0-9_.+-]{1,120}")
    private const val base = "https://api.modrinth.com/maven/maven/modrinth/"

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value,"UTF-8").replace("+","%20")

    fun mavenJarCandidates(
        projectId: String,
        projectSlug: String,
        versionId: String,
        versionNumber: String
    ): List<String> {
        if (!listOf(projectId,projectSlug,versionId,versionNumber)
                .all { safeToken.matches(it) }) return emptyList()
        // The public Modrinth Maven accepts project slug OR id and
        // version_number OR version id. Use the immutable identifiers first.
        return listOf(
            projectId to versionId,
            projectSlug to versionNumber
        ).distinct().map { (artifact,release) ->
            base + pathSegment(artifact) + "/" + pathSegment(release) +
                "/" + pathSegment(artifact) + "-" + pathSegment(release) + ".jar"
        }
    }
}
