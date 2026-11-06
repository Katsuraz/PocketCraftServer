package com.pocketcraft.server

import java.util.Locale

/**
 * Four opt-in, locally persisted console shortcuts. Saving a configuration
 * never sends any command; execution requires a separate explicit tap.
 */
internal object ConsoleQuickActions {
    const val COUNT = 4
    data class Action(val label: String, val command: String)
    val defaults = listOf(
        Action("PLAYERS", "list"),
        Action("SAVE WORLD", "save-all"),
        Action("WORLD SEED", "seed"),
        Action("CHUNKY STATUS", "chunky progress")
    )
    private val destructiveRoots = setOf(
        "stop", "op", "deop", "kick", "ban", "ban-ip", "pardon", "pardon-ip",
        "whitelist", "save-off", "kill", "fill", "setblock", "execute",
        "function", "reload"
    )

    fun labelKey(slot: Int): String {
        require(slot in 0 until COUNT)
        return "console_quick_label_" + slot
    }
    fun commandKey(slot: Int): String {
        require(slot in 0 until COUNT)
        return "console_quick_command_" + slot
    }

    fun validated(label: String, command: String): Action {
        val trimmedLabel = label.trim()
        require(trimmedLabel.isNotEmpty() && trimmedLabel.length <= 24 &&
            trimmedLabel.none { Character.isISOControl(it) }) {
            "Shortcut labels must be 1–24 printable characters"
        }
        return Action(trimmedLabel, ConsoleCommands.normalize(command))
    }

    fun needsConfirmation(slot: Int, action: Action): Boolean {
        require(slot in 0 until COUNT)
        val root = action.command.substringBefore(' ').lowercase(Locale.ROOT)
        return action != defaults[slot] || root in destructiveRoots
    }
}
