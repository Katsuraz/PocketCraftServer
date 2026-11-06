package com.pocketcraft.server

/** The stable channel excludes prereleases; Beta users also receive newer stable builds. */
internal object UpdateChannels {
    const val KEY="updateChannel"
    const val STABLE="stable"
    const val BETA="beta"
    fun normalize(value: String?): String = if(value==BETA) BETA else STABLE
    fun accepts(channel: String?, prerelease: Boolean): Boolean =
        !prerelease || normalize(channel)==BETA
    fun isBetaVersion(name: String): Boolean =
        Regex("^\\d+\\.\\d+\\.\\d+-(beta|rc)\\.\\d+$").matches(name)
}
