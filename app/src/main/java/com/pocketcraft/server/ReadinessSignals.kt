package com.pocketcraft.server

/**
 * Parse readiness signals from actual Minecraft / Geyser log lines.
 * A live JVM alone is NOT evidence that the server accepts connections.
 */
internal class ReadinessSignals {
    var javaReady: Boolean = false
        private set
    var bedrockListener: Boolean = false
        private set
    var nethernetRegistered: Boolean = false
        private set
    var nethernetNativeFailure: Boolean = false
        private set

    fun reset() {
        javaReady = false
        bedrockListener = false
        nethernetRegistered = false
        nethernetNativeFailure = false
    }

    fun observe(line: String) {
        // Minecraft dedicated server emits "Done (N.NNs)! For help, type ..."
        if (line.contains("Done (") && line.contains("For help", ignoreCase = true)) {
            javaReady = true
        }
        // Geyser's listener messages vary across versions.
        // Only count the line if it names Geyser and confirms a listening port.
        val lower = line.lowercase(java.util.Locale.ROOT)
        if ((lower.contains("nethernet will not start") &&
            (lower.contains("webrtc") || lower.contains("native library"))) ||
            lower.contains("cannot identify the jvm's libc")) {
            nethernetNativeFailure = true
        }
        if (lower.contains("registered with the external signaling service")) {
            nethernetRegistered = true
        }
        if (lower.contains("geyser") && (
            (lower.contains("started") && lower.contains("port")) ||
            (lower.contains("listening") && lower.contains("19132")) ||
            lower.contains("started geyser on")
        )) {
            bedrockListener = true
        }
    }

    fun state(): String = when {
        javaReady && bedrockListener -> "JAVA + BEDROCK READY"
        javaReady -> "JAVA READY"
        else -> "STARTING"
    }
}
