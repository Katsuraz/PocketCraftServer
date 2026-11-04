package com.pocketcraft.server

/**
 * Validates an explicitly user-provided credential for an existing Playit
 * agent. No networking, persistence or logging occurs in this utility.
 */
internal object PlayitSecretFormat {
    private val tomlLine = Regex("""^secret_key *= *["']([0-9a-fA-F]+)["'] *$""")

    fun normalize(text: String): String {
        val raw = text.trim()
        val secret = tomlLine.matchEntire(raw)?.groupValues?.get(1) ?: raw
        require(secret.length in 32..512 && secret.length % 2 == 0 &&
            secret.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid Playit agent secret. Paste the hex key from your own agent's playit.toml."
        }
        return secret
    }

    fun toToml(secret: String): String =
        "secret_key = " + 34.toChar() + normalize(secret) + 34.toChar() + 10.toChar()
}
