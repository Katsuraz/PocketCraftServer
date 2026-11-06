package com.pocketcraft.server

/** Best-effort sanitization for logs before optional sharing. Never a security boundary. */
internal object PrivateLogRedactor {
    private val nxs = Regex("""(?i)\bpnx1\.[A-Za-z0-9_.-]{8,}""")
    private val labeled = Regex(
        """(?i)(\b(?:token|secret|password|api[_-]?key|authorization|bearer)\b\s*[:=]\s*['"]?)([^\s'",;]+)"""
    )
    private val bearer = Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+/\-=]{12,}""")
    fun redact(raw: String): String {
        val nxsMasked=nxs.replace(raw,"[NXS TOKEN REDACTED]")
        val bearerMasked=bearer.replace(nxsMasked) { it.groupValues[1]+"[REDACTED]" }
        return labeled.replace(bearerMasked) { it.groupValues[1]+"[REDACTED]" }
    }
}
