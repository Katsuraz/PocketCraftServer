package com.pocketcraft.server

/**
 * Select a dependency version independently of the parent mod's release.
 *
 * A default Modrinth pin is respected unless the user explicitly chooses
 * "compatible Fabric" or picks another immutable version ID. This policy
 * works for every node of a recursive dependency graph.
 */
internal object ModrinthDependencySelection {
    fun choose(projectId: String, publishedPin: String?,
               exactVersions: Map<String,String>,
               preferCompatible: Set<String>): String? {
        require(projectId.matches(Regex("[A-Za-z0-9_-]{1,120}"))) {
            "Invalid Modrinth dependency project ID"
        }
        require(projectId !in preferCompatible || projectId !in exactVersions) {
            "A dependency cannot have both an exact version and auto-selection"
        }
        return if (projectId in preferCompatible) null
            else exactVersions[projectId] ?: publishedPin
    }
}
