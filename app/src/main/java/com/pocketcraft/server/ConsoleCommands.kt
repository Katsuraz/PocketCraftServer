package com.pocketcraft.server

/**
 * Command validation shared by the Console UI and the foreground server service.
 * Minecraft's server stdin expects a command without the in-game leading slash.
 */
internal object ConsoleCommands {
    fun normalize(raw: String): String {
        // Support copied in-game commands, while retaining arguments exactly.
        val command = raw.trim().trimStart('/').trimStart()
        require(command.isNotBlank()) { "Enter a command first" }
        require(command.length <= 240) { "Commands must be at most 240 characters" }
        require(command.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "Only one command may be sent at a time"
        }
        return command
    }
}
