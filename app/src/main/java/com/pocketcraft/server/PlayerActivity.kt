package com.pocketcraft.server

/**
 * Conservative live players observed from Minecraft's server-thread messages.
 * A raw /list response is useful on versions that render command replies as
 * "System chat: ...". Chat text and malformed lists must not replace the roster.
 */
internal object PlayerActivity {
    private val lock = Any()
    private val online = linkedSetOf<String>()
    @Volatile var revision = 0
        private set
    @Volatile var maxPlayers: Int? = null
        private set

    // Parse only real server-thread INFO records, never arbitrary player chat.
    private val serverLine = Regex(
        """^(?:\[[0-9:. -]{1,40}\]\s+)?\[Server thread/INFO\]:\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val joined = Regex("""^([^\s]+) joined the game$""", RegexOption.IGNORE_CASE)
    private val left = Regex("""^([^\s]+) left the game$""", RegexOption.IGNORE_CASE)
    private val list = Regex(
        """^There are (\d+) of a max of (\d+) players online:?\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val allowed = Regex("[A-Za-z0-9_.+*~-]{1,48}")

    fun reset() = synchronized(lock) {
        online.clear()
        maxPlayers = null
        revision++
    }
    fun names(): List<String> = synchronized(lock) { online.toList() }
    fun count(): Int = synchronized(lock) { online.size }

    fun observe(raw: String) {
        val message = serverLine.matchEntire(raw)?.groupValues?.get(1)?.trim() ?: return
        // Java 1.21+ wraps console-command replies in "System chat: ".
        // Join/leave notifications themselves are plain server messages.
        val listMessage = message.removePrefix("System chat: ").trim()
        val status = list.matchEntire(listMessage)
        if (status != null) {
            val reported = status.groupValues[1].toIntOrNull() ?: return
            val limit = status.groupValues[2].toIntOrNull()?.takeIf { it in 1..300 }
                ?: return
            if (reported > limit || reported > 300) return
            val players = status.groupValues[3].split(',').map { it.trim() }
                .filter { allowed.matches(it) }
            // Do not replace a known roster using incomplete/truncated or
            // malformed list output. An empty 0-player response is valid.
            if (players.size != reported || players.toSet().size != reported) return
            synchronized(lock) {
                val changed = online.toList() != players || maxPlayers != limit
                online.clear()
                online.addAll(players)
                maxPlayers = limit
                if (changed) revision++
            }
            return
        }
        joined.matchEntire(message)?.groupValues?.get(1)?.takeIf { allowed.matches(it) }
            ?.let { name -> synchronized(lock) { if (online.add(name)) revision++ } }
        left.matchEntire(message)?.groupValues?.get(1)?.takeIf { allowed.matches(it) }
            ?.let { name -> synchronized(lock) { if (online.remove(name)) revision++ } }
    }
}
