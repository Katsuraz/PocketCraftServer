package com.pocketcraft.server

import java.io.IOException

/**
 * Explicitly approved recovery for pinned Modrinth dependency versions.
 *
 * Never changes the parent mod's selected version. If the EXACT pinned
 * dependency file (or version metadata) returns 404/410, only then try a
 * normal Fabric + current Minecraft version resolution of the SAME project.
 * Checksum failures, transport failures, nested dependency errors and all
 * non-404 statuses are intentionally NOT silently replaced.
 */
internal object ModrinthDependencyRecovery {
    internal fun isUnavailable(failure: IOException): Boolean {
        val message = failure.message.orEmpty()
        return message.contains("HTTP 404",ignoreCase=true) ||
            message.contains("HTTP 410",ignoreCase=true)
    }

    internal fun canRecover(
        pinnedVersionId: String?,
        userApproved: Boolean,
        failure: IOException,
        isNestedDependencyError: Boolean
    ): Boolean = userApproved &&
        !pinnedVersionId.isNullOrBlank() &&
        !isNestedDependencyError &&
        isUnavailable(failure)

    fun <T> attempt(
        pinnedVersionId: String?,
        userApproved: Boolean,
        first: () -> T,
        compatible: () -> T,
        onRecover: () -> Unit = {},
        isNestedDependencyError: (IOException) -> Boolean = { false }
    ): T {
        try {
            return first()
        } catch (missing: IOException) {
            if (!canRecover(pinnedVersionId,userApproved,missing,
                    isNestedDependencyError(missing))) throw missing
            onRecover()
            try {
                return compatible()
            } catch (secondary: IOException) {
                throw IOException(
                    "Pinned release was unavailable (404/410), and a compatible " +
                    "Fabric dependency could not be installed: " +
                    (secondary.message ?: "no compatible release"),secondary)
            }
        }
    }
}
