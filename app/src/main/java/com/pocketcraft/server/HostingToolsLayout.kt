package com.pocketcraft.server

/** Real UI choices for Home → Hosting & Tools. No server behavior changes. */
internal object HostingToolsLayout {
    enum class Mode(val id: String, val label: String) {
        SIMPLE("simple", "Minimal"),
        CARDS("cards", "Cards"),
        CLASSIC("classic", "Detailed")
    }
    enum class Section(val id: String, val label: String, val symbol: String) {
        FRIENDS("friends", "Friends", "↗"),
        LOCAL("local", "Local", "⌂"),
        PLAYIT("playit", "Playit", "◎"),
        SERVER("server", "Server", "⚙"),
        TOOLS("tools", "Tools", "▤"),
        HEALTH("health", "Health", "✓")
    }
    const val MODE_KEY = "hostingToolsMode"
    const val SHOW_PREFIX = "hostingToolsVisible_"

    fun mode(id: String?): Mode = Mode.values().firstOrNull { it.id == id } ?: Mode.SIMPLE
    fun key(section: Section): String = SHOW_PREFIX + section.id
    fun visible(enabled: (Section) -> Boolean): List<Section> =
        Section.values().filter(enabled).toList()
}
