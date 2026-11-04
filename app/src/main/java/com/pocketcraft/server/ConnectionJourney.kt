package com.pocketcraft.server

/**
 * A safe connection guide: public address assignment is not a verified connection.
 */
internal object ConnectionJourney {
    enum class Target(val label: String) {
        JAVA_LAN("Java - same Wi-Fi"),
        JAVA_ONLINE("Java - internet"),
        BEDROCK_LAN("Bedrock - same Wi-Fi"),
        BEDROCK_ONLINE("Bedrock - internet");

        val bedrock get() = this == BEDROCK_LAN || this == BEDROCK_ONLINE
        val online get() = this == JAVA_ONLINE || this == BEDROCK_ONLINE
    }

    enum class Action {
        START_SERVER, OPEN_CONSOLE, OPEN_MODS, OPEN_NETWORK_SETTINGS,
        START_PLAYIT, CONNECT_PLAYIT, VIEW_PLAYIT, COPY_ADDRESS
    }

    data class State(
        val javaReady: Boolean,
        val serverRunning: Boolean,
        val geyserInstalled: Boolean,
        val bedrockListener: Boolean,
        val nethernetNativeFailure: Boolean,
        val nethernetRegistered: Boolean,
        val lanIp: String?,
        val javaPort: String,
        val playitRunning: Boolean,
        val playitAccountConnected: Boolean,
        val playitClaimAvailable: Boolean,
        val javaPublicAddress: String?,
        val bedrockPublicAddress: String?
    )

    data class Guidance(
        val title: String,
        val detail: String,
        val actionLabel: String,
        val action: Action,
        val address: String? = null
    )

    fun assess(target: Target, state: State): Guidance {
        if (!state.javaReady) {
            return if (state.serverRunning) Guidance(
                "Minecraft is still starting",
                "Wait for Java Ready in the Console. A running process does not prove that it is joinable.",
                "OPEN CONSOLE", Action.OPEN_CONSOLE
            ) else Guidance(
                "Start your Minecraft server",
                "PocketCraft hosts Minecraft directly on your phone. Start Fabric first.",
                "START SERVER", Action.START_SERVER
            )
        }
        if (target.bedrock) {
            if (!state.geyserInstalled) return Guidance(
                "Install the Bedrock bridge",
                "This Fabric server needs Geyser and compatible Fabric API. Check the Mod Library.",
                "OPEN MOD LIBRARY", Action.OPEN_MODS
            )
            if (state.nethernetNativeFailure) return Guidance(
                "Android WebRTC library failed",
                "Geyser loaded, but NetherNet failed on Android. Changing Playit tokens or ports cannot repair this native library error. Check Console.",
                "VIEW FAILURE IN CONSOLE", Action.OPEN_CONSOLE
            )
            if (!state.bedrockListener) return Guidance(
                "Bedrock listener not confirmed",
                "Geyser might still be loading or its NetherNet transport may have failed. Check Console before sharing this address.",
                "OPEN CONSOLE", Action.OPEN_CONSOLE
            )
        }
        if (!target.online) {
            val ip = state.lanIp?.takeIf { it.isNotBlank() } ?: return Guidance(
                "Connect your phone to Wi-Fi",
                "Local players must reach the same Wi-Fi or hotspot network.",
                "OPEN WI-FI SETTINGS", Action.OPEN_NETWORK_SETTINGS
            )
            val address = if (target.bedrock) ip + ":19132" else ip + ":" + state.javaPort
            return Guidance(
                "LAN address ready to test",
                "The local listener was reported ready. Join from another device on the same network to confirm.",
                "COPY LAN ADDRESS", Action.COPY_ADDRESS, address
            )
        }
        if (!state.playitRunning) return Guidance(
            "Start internet hosting",
            "Start Playit on this phone. You may need to approve the account once.",
            "START PLAYIT", Action.START_PLAYIT
        )
        if (!state.playitAccountConnected) return Guidance(
            "Connect your Playit account",
            "Approve PocketCraft's agent from the Playit browser link. Never share an agent secret.",
            if (state.playitClaimAvailable) "APPROVE PLAYIT AGENT" else "OPEN PLAYIT SETUP",
            if (state.playitClaimAvailable) Action.CONNECT_PLAYIT else Action.VIEW_PLAYIT
        )
        if (target.bedrock && !state.nethernetRegistered) return Guidance(
            "NetherNet signaling not confirmed",
            "Internet Bedrock requires NetherNet signaling. A Playit address alone does not prove that this works.",
            "CHECK CONSOLE", Action.OPEN_CONSOLE
        )
        val address = (if (target.bedrock) state.bedrockPublicAddress else state.javaPublicAddress)
            ?.takeIf { it.isNotBlank() }
            ?: return Guidance(
                "Assign a public tunnel",
                "Playit is connected, but a public address has not been assigned for this edition.",
                "VIEW PLAYIT SETUP", Action.VIEW_PLAYIT
            )
        return Guidance(
            "Public address assigned — test it",
            "This is an assigned, unverified address. Have someone test from another network before treating the server as publicly joinable.",
            "COPY PUBLIC ADDRESS", Action.COPY_ADDRESS, address
        )
    }
}
