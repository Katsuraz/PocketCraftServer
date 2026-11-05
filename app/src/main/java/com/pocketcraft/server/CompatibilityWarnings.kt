package com.pocketcraft.server

import java.util.Locale

/** Converts known Android/JVM compatibility problems into local, non-secret advice. */
internal class CompatibilityWarnings {
    private val observed = linkedSetOf<String>()

    fun reset() = observed.clear()

    fun observe(line: String) {
        val text = line.lowercase(Locale.ROOT)
        when {
            text.contains("could not initialize class com.sun.jna.native") ||
                (text.contains("c2me") && text.contains("oshi")) ->
                observed += "C2ME/OSHI could not load JNA to read Android CPU details. If Minecraft still starts, this warning is non-fatal. Review C2ME compatibility."
            text.contains("same-directory hard links") ||
                text.contains("lod service remains disabled") ->
                observed += "LOD settings cannot be installed using hard links on this Android filesystem. The optional LOD service is disabled; check the mod's Android support."
            text.contains("zstd-1 native cannot load") ->
                observed += "The LOD mod's native zstd library did not load; it is using uncompressed data."
            text.contains("can't keep up!") || text.contains("can't keep up!") ->
                observed += "Minecraft reported missed ticks. Check phone thermal status, memory pressure and modpack load; startup spikes alone do not prove constant lag."
        }
    }

    fun messages(): List<String> = observed.toList()
}
