package com.pocketcraft.server

import java.util.ArrayDeque

/**
 * Small, in-memory command transcript independent from full server startup logs.
 *
 * Responses are messages observed shortly after a command. They may include
 * concurrent server events and must never be presented as an execution guarantee.
 * Raw Minecraft output remains available through the full console log.
 */
internal object ConsoleCommandActivity {
    private const val MAX_LINES = 35
    private const val MAX_CAPTURE_LINES = 14
    private const val CAPTURE_WINDOW_MS = 20_000L
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var captureDeadline = 0L
    private var captured = 0
    private var overflowNoted = false

    @Volatile var revision = 0
        private set

    fun reset() = synchronized(lock) {
        lines.clear()
        captureDeadline = 0L
        captured = 0
        overflowNoted = false
        revision++
    }

    fun snapshot(): String = synchronized(lock) {
        if (lines.isEmpty()) "No commands sent in this server session.\nTry LIST or type a command above."
        else lines.joinToString("\n")
    }

    /** Show the newest activity first in the compact panel, not stale lines. */
    fun preview(maxLines: Int = 18): String = synchronized(lock) {
        if (lines.isEmpty()) "No commands sent in this server session.\nTry LIST or type a command above."
        else lines.toList().takeLast(maxLines.coerceIn(1, MAX_LINES)).joinToString("\n")
    }

    private fun add(message: String) {
        lines.addLast(message.take(350))
        while (lines.size > MAX_LINES) lines.removeFirst()
        revision++
    }

    fun queued(command: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        add("› " + command)
        captureDeadline = nowMs + CAPTURE_WINDOW_MS
        captured = 0
        overflowNoted = false
    }

    fun delivered() = synchronized(lock) {
        add("✓ Written to Minecraft stdin — response below, if any")
    }

    fun failed(message: String) = synchronized(lock) {
        add("✕ " + message)
        captureDeadline = 0L
    }

    /** The long command-list dump must never bury users' actual commands. */
    fun isHelpListing(line: String): Boolean {
        val message = line.substringAfter("]: ", line).trim()
            .removePrefix("[PocketCraft] ").removePrefix("System chat: ").trim()
        if (message.startsWith("/") && message.length > 1 &&
            (message[1].isLetter() || message[1] == '_')) return true
        if (message.equals("Chunky Commands", ignoreCase = true)) return true
        if (message.startsWith("chunky ", ignoreCase = true) &&
            " - " in message) return true
        return false
    }

    /** Conservative server-reply selection, never claiming exclusive attribution. */
    fun observe(raw: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (captureDeadline == 0L || nowMs > captureDeadline) return@synchronized
        if (isHelpListing(raw)) return@synchronized
        val message = raw.substringAfter("]: ", "").trim()
        if (message.isEmpty()) return@synchronized
        val serverLine = raw.contains("[Server thread/INFO]", ignoreCase = true) ||
            raw.contains("[Server thread/WARN]", ignoreCase = true) ||
            raw.contains("[Server thread/ERROR]", ignoreCase = true)
        if (!serverLine) return@synchronized

        val systemChat = message.startsWith("System chat: ")
        val reply = if (systemChat) message.removePrefix("System chat: ").trim() else message
        if (reply.isBlank()) return@synchronized
        // Help catalogs and long usage listings belong in Full Logs, not in
        // the compact command reply history.
        if (reply.startsWith("-------- Help:", ignoreCase = true) ||
            reply.startsWith("Showing help page ", ignoreCase = true)) return@synchronized
        val relevant = systemChat || raw.contains("/WARN]") || raw.contains("/ERROR]") ||
            reply.contains("Unknown or incomplete command", ignoreCase = true) ||
            reply.contains("Unknown command", ignoreCase = true) ||
            reply.contains("players online", ignoreCase = true) ||
            reply.contains("issued server command", ignoreCase = true)
        if (!relevant) return@synchronized
        if (captured < MAX_CAPTURE_LINES) {
            add("  " + reply)
            captured++
        } else if (!overflowNoted) {
            add("  … More messages available under Full Logs")
            overflowNoted = true
        }
    }
}
