package com.pocketcraft.server

/**
 * A truthful, dependency-free snapshot of hosting readiness.
 *
 * An installed runtime, running Java process, assigned Playit hostname or
 * Geyser JAR does not prove that a player can join the server.
 */
internal object HostingHealth {
    enum class Level { GOOD, ACTION, INFO }

    data class Snapshot(
        val fabricInstalled: Boolean,
        val javaInstalled: Boolean,
        val eulaAccepted: Boolean,
        val geyserInstalled: Boolean,
        val floodgateInstalled: Boolean,
        val processRunning: Boolean,
        val javaReady: Boolean,
        val bedrockListening: Boolean,
        val nethernetRegistered: Boolean,
        val playitActive: Boolean,
        val javaTunnelAddress: String?,
        val bedrockTunnelAddress: String?,
        val localIp: String?,
        val lastError: String?
    )

    data class Check(
        val title: String,
        val explanation: String,
        val level: Level
    )

    fun evaluate(s: Snapshot): List<Check> {
        val checks = mutableListOf<Check>()
        checks += Check("Fabric server", if(s.fabricInstalled) "Server launcher installed"
            else "Install a Fabric server from the Home setup guide",
            if(s.fabricInstalled) Level.GOOD else Level.ACTION)
        checks += Check("Java engine", if(s.javaInstalled) "Runtime files installed; run Test Java Engine if startup fails"
            else "Install the Android ARM64 Java runtime",
            if(s.javaInstalled) Level.GOOD else Level.ACTION)
        checks += Check("Minecraft EULA", if(s.eulaAccepted) "Agreement recorded"
            else "Read and accept Minecraft's EULA before starting",
            if(s.eulaAccepted) Level.GOOD else Level.ACTION)
        checks += Check("Java multiplayer", when {
            s.javaReady -> "Minecraft reported ready for Java connections"
            s.processRunning -> "Java process started; waiting for Minecraft's Done message"
            else -> "Server is not running; start it after setup"
        }, when { s.javaReady -> Level.GOOD; s.processRunning -> Level.INFO; else -> Level.INFO })
        checks += Check("Bedrock bridge", when {
            !s.geyserInstalled -> "Install Geyser-Fabric; Java players can still join without it"
            s.bedrockListening -> "Geyser reported a listening Bedrock port"
            else -> "Geyser JAR found; confirm it loaded in the Console"
        }, when { !s.geyserInstalled -> Level.ACTION; s.bedrockListening -> Level.GOOD; else -> Level.INFO })
        checks += Check("Floodgate", if(s.floodgateInstalled)
            "Floodgate mod found; this does not confirm Bedrock authentication"
            else "Floodgate mod missing; Bedrock authentication may require a Java login",
            if(s.floodgateInstalled) Level.GOOD else Level.INFO)
        checks += Check("Phone LAN address", if(s.localIp.isNullOrBlank())
            "Connect to Wi-Fi or a phone hotspot to share a LAN address"
            else "Local connection: " + s.localIp + " (same network only)",
            if(s.localIp.isNullOrBlank()) Level.INFO else Level.GOOD)
        checks += Check("Remote Java / Playit", when {
            !s.javaTunnelAddress.isNullOrBlank() && s.playitActive ->
                "Address assigned: " + s.javaTunnelAddress + ". Test from another network"
            s.playitActive -> "Playit agent active, but no Java address confirmed"
            else -> "Optional: Playit is offline. LAN hosting does not need it"
        }, if(!s.javaTunnelAddress.isNullOrBlank() && s.playitActive) Level.INFO else Level.INFO)
        checks += Check("Remote Bedrock / NetherNet", when {
            !s.bedrockTunnelAddress.isNullOrBlank() && s.nethernetRegistered ->
                "Tunnel assigned and external signaling registered; test a Bedrock login"
            !s.bedrockTunnelAddress.isNullOrBlank() ->
                "Tunnel exists, but NetherNet registration not confirmed"
            s.nethernetRegistered ->
                "External signaling registered; no public Bedrock tunnel found"
            else -> "For remote Bedrock, configure Geyser and its NetherNet token"
        }, Level.INFO)
        if (!s.lastError.isNullOrBlank()) checks += Check("Last server error",
            s.lastError.take(240), Level.ACTION)
        return checks
    }

    /** Blockers for starting Java only; crossplay and internet are optional. */
    fun startBlockers(s: Snapshot): Int =
        listOf(s.fabricInstalled,s.javaInstalled,s.eulaAccepted).count { !it }
}
