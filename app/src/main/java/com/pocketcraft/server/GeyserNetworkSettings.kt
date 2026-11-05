package com.pocketcraft.server

import java.io.File

/**
 * Reads only public network settings from Geyser-Fabric's existing config.
 * Does not parse, copy, log or expose tokens, auth keys or signaling credentials.
 */
internal object GeyserNetworkSettings {
    data class Settings(
        val bedrockPort: Int = 19132,
        val webRtcPort: Int = 19132,
        val transport: String = "unknown",
        val configured: Boolean = false
    ) {
        fun directRouterAdvice(): String =
            when (transport) {
                "raknet" -> "UDP $bedrockPort (RakNet)"
                "nethernet" -> "TCP $bedrockPort (NetherNet signaling) and UDP $webRtcPort (gameplay)"
                "both" -> "TCP $bedrockPort (NetherNet signaling), UDP $webRtcPort (gameplay), and UDP $bedrockPort (RakNet)"
                else -> "For NetherNet: TCP $bedrockPort plus UDP $webRtcPort; for RakNet: UDP $bedrockPort"
            }
    }

    fun read(file: File): Settings =
        try {
            if (!file.isFile || file.length() > 128_000L) Settings()
            else parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { Settings() }

    /** Minimal YAML subset; only top-level bedrock fields, never nested signaling fields. */
    fun parse(config: String): Settings {
        if (config.length > 128_000) return Settings()
        var inBedrock = false
        var port = 19132
        var webRtc: Int? = null
        var transport = "unknown"
        var found = false
        for (line in config.lineSequence()) {
            val noComment = line.substringBefore('#').trimEnd()
            if (noComment.isBlank()) continue
            val indentation = noComment.length - noComment.trimStart().length
            val body = noComment.trim()
            if (indentation == 0) {
                inBedrock = body == "bedrock:"
                if (inBedrock) found = true
                continue
            }
            // All direct Geyser bedrock config properties have two-space indent.
            if (!inBedrock || indentation != 2) continue
            val key = body.substringBefore(':', "").trim()
            val value = body.substringAfter(':', "").trim().trim('"', '\'')
            when (key) {
                "port" -> value.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port = it }
                "webrtc-port" -> value.toIntOrNull()?.takeIf { it in 0..65535 }?.let { webRtc = it }
                "transport" -> if (value.lowercase() in setOf("raknet", "nethernet", "both"))
                    transport = value.lowercase()
            }
        }
        return Settings(port, webRtc?.takeIf { it != 0 } ?: port, transport, found)
    }
}
