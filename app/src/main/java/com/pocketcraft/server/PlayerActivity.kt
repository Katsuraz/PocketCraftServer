package com.pocketcraft.server

import java.util.Locale

/**
 * A conservative, live list of Minecraft players observed in the server log.
 * No offline-player lookups, UUID guessing, or fabricated TPS/latency data.
 */
internal object PlayerActivity {
    private val lock=Any()
    private val online=linkedSetOf<String>()
    @Volatile var revision = 0
        private set
    @Volatile var maxPlayers: Int? = null
        private set

    private val joined=Regex("""\]:\s*([^\s]+) joined the game(?:\s|$)""",RegexOption.IGNORE_CASE)
    private val left=Regex("""\]:\s*([^\s]+) left the game(?:\s|$)""",RegexOption.IGNORE_CASE)
    private val list=Regex("""\]:\s*There are (\d+) of a max of (\d+) players online:?\s*(.*)$""",RegexOption.IGNORE_CASE)
    private val allowed=Regex("[A-Za-z0-9_.+*~-]{1,48}")
    fun reset() = synchronized(lock) {
        online.clear()
        maxPlayers=null
        revision++
    }
    fun names(): List<String> = synchronized(lock) { online.toList() }
    fun count(): Int = synchronized(lock) { online.size }

    fun observe(raw: String) {
        // Only use actual server-thread status lines, not a copied chat command.
        if(!raw.contains("[Server thread/INFO]",true)) return
        val m=list.find(raw)
        if(m!=null) {
            val reported=m.groupValues[1].toIntOrNull() ?: return
            val limit=m.groupValues[2].toIntOrNull()
            val players=m.groupValues[3].split(',').map { it.trim() }
                .filter { allowed.matches(it) }.take(300)
            if(reported>300) return
            synchronized(lock) {
                val changed=online.toList()!=players || maxPlayers!=limit
                online.clear()
                online.addAll(players)
                maxPlayers=limit
                if(changed)revision++
            }
            return
        }
        joined.find(raw)?.groupValues?.getOrNull(1)?.takeIf { allowed.matches(it) }?.let {
            synchronized(lock){if(online.add(it))revision++}
        }
        left.find(raw)?.groupValues?.getOrNull(1)?.takeIf { allowed.matches(it) }?.let {
            synchronized(lock){if(online.remove(it))revision++}
        }
    }
}
