package com.pocketcraft.server

import java.io.File

/**
 * Public, non-secret Geyser network configuration. Never read/log NXS tokens,
 * private keys, passwords or URLs containing credentials.
 */
internal object GeyserNetworkSettings {
    data class Settings(
        val bedrockPort: Int = 19132,
        val webRtcPort: Int = 19132,
        val transport: String = "unknown",
        val configured: Boolean = false,
        val signalingMode: String = "unknown",
        val warnings: List<String> = emptyList()
    ) {
        /** A local-IP NetherNet join requires built-in signaling (or RakNet). */
        fun localJoinSupported(): Boolean = when (transport) {
            "raknet", "both" -> true
            "nethernet" -> signalingMode == "builtin" || signalingMode == "hybrid"
            else -> true // Missing config: offer a test, never claim it works.
        }

        fun directRouterAdvice(): String = when (transport) {
            "raknet" -> "UDP $bedrockPort (RakNet)"
            "nethernet" -> "TCP $bedrockPort (NetherNet built-in signaling) and UDP $webRtcPort (gameplay)"
            "both" -> "TCP $bedrockPort (NetherNet signaling), UDP $webRtcPort (gameplay), and UDP $bedrockPort (RakNet)"
            else -> "For NetherNet: TCP $bedrockPort plus UDP $webRtcPort; for RakNet: UDP $bedrockPort"
        }
    }

    fun read(file: File): Settings = try {
        if (!file.isFile || file.length() > 128_000L) Settings()
        else parse(file.readText(Charsets.UTF_8))
    } catch (_: Exception) { Settings() }

    /** Limited indentation-aware YAML scan. Never materializes credential values. */
    fun parse(config: String): Settings {
        if (config.length > 128_000) return Settings(warnings = listOf("Geyser configuration is too large to check."))
        var inBedrock = false
        var inSignaling = false
        var port = 19132
        var webRtc: Int? = null
        var transport = "unknown"
        var mode = "unknown"
        var found = false
        var modes = 0
        var transports = 0
        val warnings = mutableListOf<String>()
        for (line in config.lineSequence()) {
            val noComment = line.substringBefore('#').trimEnd()
            if (noComment.isBlank()) continue
            val indent = noComment.length - noComment.trimStart().length
            val body = noComment.trim()
            if (indent == 0) {
                inBedrock = body == "bedrock:"
                inSignaling = false
                if (inBedrock) found = true
                if (body.startsWith("advertise-addresses:"))
                    warnings += "Advertised addresses appear at the YAML root instead of the Geyser signaling section."
                continue
            }
            if (!inBedrock) continue
            if (indent == 2) {
                inSignaling = body == "signaling:"
                val key = body.substringBefore(':', "").trim()
                val value = body.substringAfter(':', "").trim().trim('"', '\'')
                when (key) {
                    "port" -> value.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port = it }
                        ?: run { warnings += "Invalid Bedrock port; check config.yml." }
                    "webrtc-port" -> value.toIntOrNull()?.takeIf { it in 0..65535 }?.let { webRtc = it }
                        ?: run { warnings += "Invalid WebRTC port; check config.yml." }
                    "transport" -> {
                        transports++
                        if (value.lowercase() in setOf("raknet", "nethernet", "both")) transport = value.lowercase()
                        else warnings += "Unsupported Bedrock transport; check config.yml."
                    }
                }
            } else if (indent == 4 && inSignaling && body.startsWith("mode:")) {
                modes++
                mode = body.substringAfter(':').trim().trim('"', '\'').lowercase()
            }
        }
        if (modes > 1) warnings += "Duplicate signaling.mode keys: YAML may silently select the wrong one."
        if (transports > 1) warnings += "Duplicate bedrock.transport keys: remove the extra entry."
        if (mode != "unknown" && mode !in setOf("builtin", "nxs", "hybrid", "none"))
            warnings += "Unknown NetherNet signaling mode."
        if (transport == "nethernet" && mode == "nxs")
            warnings += "External-only NXS signaling does not provide the built-in local-IP join endpoint. For LAN and Playit, review hybrid signaling (with your provider settings)."
        if (transport == "nethernet" && mode == "none")
            warnings += "NetherNet signaling is disabled; Bedrock discovery may fail."
        if (transport == "both" && (webRtc == null || webRtc == 0 || webRtc == port))
            warnings += "RakNet and NetherNet should use different UDP ports. Configure webrtc-port."
        return Settings(port, webRtc?.takeIf { it != 0 } ?: port, transport, found, mode, warnings.distinct())
    }
}
