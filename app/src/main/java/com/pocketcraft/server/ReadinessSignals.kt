package com.pocketcraft.server

import java.util.Locale

/**
 * Actual evidence from this Minecraft process, never merely the presence of a JAR.
 * Geyser can start via RakNet, NetherNet built-in signaling, external signaling,
 * or both. Its published startup text differs between transports and versions.
 */
internal class ReadinessSignals {
    @Volatile var javaReady = false
        private set
    @Volatile var bedrockListener = false
        private set
    @Volatile var nethernetRegistered = false
        private set
    @Volatile var nethernetBuiltIn = false
        private set
    @Volatile var nethernetNativeFailure = false
        private set
    @Volatile var bedrockPlayerJoined = false
        private set
    @Volatile var userConfirmedBedrock = false
        private set
    @Volatile private var raknetListener = false
    // Floodgate's default prefix is "."; a verified vanilla player login with
    // this prefix proves that a Bedrock player reached the Java server.
    // The prefix can be changed in Floodgate config and is set before startup.
    private var floodgateUsernamePrefix = "."

    val nethernetSignalingReady: Boolean
        get() = nethernetRegistered || nethernetBuiltIn

    val nethernetSignalDescription: String
        get() = when {
            nethernetRegistered && nethernetBuiltIn -> "Built-in and external signaling reported"
            nethernetRegistered -> "External signaling registered"
            nethernetBuiltIn -> "Built-in signaling started"
            else -> "Signaling not yet reported"
        }

    val bedrockEvidence: String
        get() = when {
            bedrockPlayerJoined -> "Bedrock player joined (Geyser/Floodgate)"
            userConfirmedBedrock -> "Bedrock join confirmed by you"
            raknetListener -> "Geyser RakNet listener reported"
            nethernetSignalingReady -> nethernetSignalDescription
            else -> "No Geyser readiness message captured"
        }

    fun reset() {
        javaReady = false
        bedrockListener = false
        raknetListener = false
        nethernetRegistered = false
        nethernetBuiltIn = false
        nethernetNativeFailure = false
        bedrockPlayerJoined = false
        userConfirmedBedrock = false
    }

    /** Configure the actual Floodgate prefix before processing live server output. */
    fun setFloodgateUsernamePrefix(prefix: String) {
        // Empty/custom prefixes cannot be reliably inferred from a vanilla login
        // without an additional Floodgate/Geyser signal.
        floodgateUsernamePrefix = prefix.takeIf {
            it.isNotEmpty() && it.length <= 8 &&
                it.none { ch -> ch.isWhitespace() || ch == '[' || ch == ']' || ch == ':' }
        } ?: ""
    }

    /** Explicit confirmation by someone who joined this running server from Bedrock. */
    fun confirmBedrockJoin() {
        userConfirmedBedrock = true
        bedrockListener = true
    }

    fun observe(line: String) {
        val lower = line.replace(ANSI, "").replace(COLOR, "").lowercase(Locale.ROOT)
        if (lower.contains("done (") && lower.contains("for help")) {
            javaReady = true
        }

        if ((lower.contains("nethernet will not start") &&
                    (lower.contains("webrtc") || lower.contains("native library"))) ||
            lower.contains("cannot identify the jvm's libc")) {
            nethernetNativeFailure = true
        }

        // From Geyser's own language bundle:
        //  "Started Geyser on 0.0.0.0:19132"
        //  "Started Geyser on UDP port 19132"
        // These refer to a successfully bound RakNet listener.
        if ((lower.contains("started geyser on ") ||
                (lower.contains("geyser started") &&
                    (lower.contains(" udp ") || lower.contains(" port ")))) &&
            !lower.contains("failed") && !lower.contains("could not") &&
            !lower.contains("unable to")) {
            raknetListener = true
        }

        // Modern NetherNet releases do not necessarily emit the RakNet startup
        // message, even while real Bedrock players can successfully connect.
        // Geyser's official NetherNet docs give this exact startup phrase.
        if (lower.contains("built-in signaling started on http://") ||
            lower.contains("built-in signaling started on https://") ||
            lower.contains("builtin signaling started on http://") ||
            lower.contains("builtin signaling started on https://")) {
            nethernetBuiltIn = true
            nethernetNativeFailure = false
        }

        if (lower.contains("registered with the external signaling service") ||
            lower.contains("successfully registered with external signaling service")) {
            nethernetRegistered = true
            nethernetNativeFailure = false
        }

        // A successful Geyser-side player join is stronger evidence than an
        // absent startup line. Restrict to Geyser-tagged log lines to avoid
        // classifying ordinary Java joins as Bedrock joins.
        val geyserTag = lower.contains("[geyser") ||
            lower.contains("geyser-fabric]") || lower.contains("[geyser-fabric")
        if (geyserTag &&
            (lower.contains("player connected with username ") ||
             lower.contains("has connected to the java server") ||
             lower.contains("has connected to remote java server") ||
             (lower.contains("bedrock player") && lower.contains(" connected")))) {
            bedrockPlayerJoined = true
        }

        // Vanilla Minecraft logs the *real* accepted login under [Server thread],
        // not the Geyser logger. A Floodgate player normally has a leading dot,
        // so otherwise successful crossplay was invisible to the status panel.
        // Require a real server "logged in with entity id" event; chat messages,
        // disconnects, Java joins, and the Floodgate mod merely being installed
        // must never count as connection evidence.
        if (floodgateUsernamePrefix.isNotEmpty() &&
            lower.contains("[server thread/info]") &&
            lower.contains(" logged in with entity id ") &&
            !lower.contains("failed") && !lower.contains("lost connection")) {
            val match = SERVER_LOGIN.find(lower)
            val player = match?.groupValues?.getOrNull(1).orEmpty()
            if (player.startsWith(floodgateUsernamePrefix.lowercase(Locale.ROOT)) &&
                player.length > floodgateUsernamePrefix.length)
                bedrockPlayerJoined = true
        }

        if (raknetListener || nethernetSignalingReady || bedrockPlayerJoined || userConfirmedBedrock)
            bedrockListener = true
    }

    fun state(): String = when {
        javaReady && bedrockListener -> "JAVA + BEDROCK READY"
        javaReady -> "JAVA READY"
        else -> "STARTING"
    }

    companion object {
        private val ANSI = Regex("\\u001b\\[[0-9;]*[A-Za-z]")
        private val COLOR = Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE)
        // Minecraft's actual login format: Name[/ip:port] logged in with entity id N
        private val SERVER_LOGIN = Regex("""\]:\s*([^\s\[\]]+)\[[^\]]*\]\s+logged in with entity id\s+\d+""")
    }
}
