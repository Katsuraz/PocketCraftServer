package com.pocketcraft.server

import java.util.Locale

/**
 * Console-side suggestions based on the running Minecraft server's own /help
 * output, including Fabric commands registered by mods. Nothing is executed
 * by this component; tapping a suggestion only fills the command input.
 *
 * Mods that do not expose their commands in /help cannot be guaranteed here.
 */
object ServerCommandHints {
    private val lock = Any()
    private val discoveredRoots = linkedSetOf<String>()
    private val discoveredUsage = linkedSetOf<String>()
    @Volatile var helpPageCount: Int = 1
        private set
    @Volatile var revision: Int = 0
        private set

    private val colorCodes = Regex("\u00a7[0-9a-fk-or]", RegexOption.IGNORE_CASE)
    private val ansiCodes = Regex("\u001b\\[[0-9;]*m")
    private val helpPagination = Regex(
        """(?i)(?:help\s+page\s+(\d+)\s*(?:of|/)\s*(\d+)|help:\s*(?:index|page)\s*\((\d+)\s*/\s*(\d+)\))"""
    )
    private val commandUsage = Regex(
        """(?:^|[\s(:])\/([A-Za-z0-9_:.+-]{1,80})((?:\s+[A-Za-z0-9_:.+-]{1,70}){0,4})"""
    )
    private val commonExamples = listOf(
        "help", "list", "say", "save-all", "save-on", "save-off",
        "time set day", "time set night", "weather clear",
        "weather rain", "difficulty normal", "gamemode creative",
        "gamemode survival", "gamerule", "tp", "give", "effect",
        "whitelist list", "whitelist add", "whitelist remove",
        "op", "deop", "kick", "ban", "pardon", "seed", "locate",
        "reload", "stop"
    )

    fun reset() {
        synchronized(lock) {
            discoveredRoots.clear()
            discoveredUsage.clear()
            helpPageCount = 1
            revision++
        }
    }

    /** Capture command names only from command-shaped Minecraft help lines. */
    fun observe(rawLine: String) {
        val clean = rawLine.replace(ansiCodes, "").replace(colorCodes, "")
        val body = clean.substringAfter("]: ", clean).trim()
        helpPagination.find(body)?.let { match ->
            val pages = (match.groupValues[2].ifBlank { match.groupValues[4] })
                .toIntOrNull()?.coerceIn(1, 24) ?: 1
            synchronized(lock) {
                if (pages > helpPageCount) { helpPageCount = pages; revision++ }
            }
        }
        if ('/' !in body) return
        val isHelpOutput =
            body.startsWith("/") || body.startsWith("- /") ||
            body.startsWith("Usage: /", true) ||
            body.startsWith("Syntax: /", true) ||
            body.startsWith("Command: /", true)
        if (!isHelpOutput) return
        val m = commandUsage.find(body) ?: return
        val root = m.groupValues[1].lowercase(Locale.ROOT)
        val usage = (root + m.groupValues[2]).trim().replace(Regex("\\s+"), " ")
        synchronized(lock) {
            var changed = discoveredRoots.add(root)
            if (usage != root && discoveredUsage.add(usage)) changed = true
            if (changed) revision++
        }
    }

    fun registered(): List<String> = synchronized(lock) { discoveredRoots.sorted() }
    fun usages(): List<String> = synchronized(lock) { discoveredUsage.sorted() }
    fun registeredCount(): Int = synchronized(lock) { discoveredRoots.size }

    /**
     * Prefer observed server suggestions. Common examples are shown as a
     * fallback only before help has been collected; mod roots are never guessed.
     */
    fun suggestions(rawInput: String, limit: Int = 6): List<String> {
        val input = rawInput.trimStart().removePrefix("/").lowercase(Locale.ROOT)
        val observed = synchronized(lock) {
            (discoveredUsage + discoveredRoots).distinct()
        }
        val templates = if (observed.isEmpty()) commonExamples else
            (observed + commonExamples.filter { sample ->
                // Built-in examples are hints only for command roots observed live.
                sample.substringBefore(' ') in observed.map { it.substringBefore(' ') }
            })
        val pool = templates.distinct().sortedWith(compareBy<String> {
            if (it == input) 0 else if (it.startsWith(input)) 1 else 2
        }.thenBy { it.length }.thenBy { it })
        if (input.isBlank()) return pool.filter { it in listOf("help","list","save-all","time set day","weather clear") }
            .take(limit)
        return pool.filter { it.startsWith(input) && it != input }.take(limit)
    }
}
