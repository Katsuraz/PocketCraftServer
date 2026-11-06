package com.pocketcraft.server

/**
 * Native PocketCraft appearance design tokens. This is UI-only: colors and
 * theme choices never modify a Minecraft world, server config or Playit token.
 */
internal object PocketThemes {
    data class Palette(
        val bg: Int, val panel: Int, val surface: Int, val muted: Int,
        val text: Int, val primary: Int, val accent: Int, val purple: Int,
        val error: Int, val border: Int, val subtle: Int
    )
    data class Theme(val id: String, val name: String, val summary: String,
                     val light: Boolean, val palette: Palette)

    private fun color(rgb: Long): Int = (0xFF000000L or rgb).toInt()
    private fun p(bg:Long,panel:Long,surface:Long,muted:Long,text:Long,
                  primary:Long,accent:Long,purple:Long,error:Long,
                  border:Long,subtle:Long): Palette =
        Palette(color(bg),color(panel),color(surface),color(muted),color(text),
            color(primary),color(accent),color(purple),color(error),
            color(border),color(subtle))

    val themes = listOf(
        Theme("homestead","Homestead","Warm woodland · original",false,
            p(0x1C1814,0x30251E,0x654A34,0xC9BA9E,0xF1E5CA,0xD6B47D,0xE1B977,0xC29A70,0xF5AA93,0x97704D,0x261D18)),
        Theme("classic","Midnight","Charcoal · blue",false,
            p(0x080E18,0x141E2C,0x223245,0xACBED1,0xF6F9FD,0x70E9B4,0x80D7FA,0xB5A5FF,0xFFA0AB,0x324459,0x182737)),
        Theme("obsidian","Obsidian","Graphite · monochrome",false,
            p(0x0D0D10,0x1B1C20,0x303238,0xB8BBC5,0xF5F5F6,0xD6D7DB,0xAFC3E8,0xA7A2C7,0xF4A5A5,0x494B54,0x15161A)),
        Theme("aurora","Aurora","Northern lights · violet",false,
            p(0x0A1624,0x15293C,0x274052,0xB7CCD8,0xF2FBFF,0x84E9D5,0x8FD6FF,0xB7A4FF,0xFFA8B0,0x416479,0x101D32)),
        Theme("nether","Nether","Crimson · blackstone",false,
            p(0x170D10,0x2D1B20,0x4C292D,0xDAC0BC,0xFFF3E9,0xFFB193,0xF7B078,0xD49AAF,0xFF7777,0x80464B,0x201117)),
        Theme("ender","Ender","Void · amethyst",false,
            p(0x100E1A,0x221B31,0x3A3154,0xC9BED9,0xF8F1FF,0xC6A5FF,0xAEBAFF,0xDE9FFF,0xF3A8C6,0x63517B,0x181323)),
        Theme("oceanic","Oceanic","Deep sea · turquoise",false,
            p(0x071C27,0x123141,0x215267,0xADD2DC,0xF0FCFD,0x75E9CE,0x8ACFF8,0xA7B6FF,0xFFA6A6,0x376A7A,0x102733)),
        Theme("minecraft","Classic Minecraft","Retro · pixel controls",false,
            p(0x242424,0x383838,0x555555,0xD0D0D0,0xFFFFFF,0xB9E8A3,0xF2DA98,0xBDB1FF,0xFFB0AB,0x8B8B8B,0x303030)),
        Theme("material","Material You","Android · dynamic color",true,
            p(0xF7F7FC,0xFFFFFF,0xE4E7F0,0x505765,0x19202A,0x355A91,0x285E7B,0x6652A3,0xB3261E,0xC7CCD6,0xEDF0F7)),
        Theme("minimal","Minimal","Simple · accessible",true,
            p(0xF6F7F8,0xFFFFFF,0xEBEDF0,0x555B64,0x1D232B,0x334D69,0x326D7D,0x66527E,0xA42626,0xCCD0D7,0xF0F2F4))
    )
    private val ids = themes.map { it.id }.toSet()
    fun valid(id: String?): Boolean = id in ids
    fun selected(id: String?): Theme = themes.firstOrNull { it.id == id } ?: themes[0]
    fun palette(id: String?): Palette = selected(id).palette

    fun parseAccent(input: String?): Int? {
        val hex = input?.removePrefix("#")?.trim().orEmpty()
        if (!Regex("[a-fA-F0-9]{6}").matches(hex)) return null
        return color(hex.toLong(16))
    }
}
