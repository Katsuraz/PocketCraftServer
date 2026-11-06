package com.pocketcraft.server

/**
 * The Playit agent's rundata display_address can describe the public UDP
 * allocation for a NetherNet tunnel, rather than the TCP HTTPS signaling
 * address that Bedrock players add to their server list.
 *
 * Playit's NetherNet/Geyser setup explicitly uses the tunnel hostname at
 * port 19132 for Bedrock clients. Keep the unmodified API value available
 * for diagnostics; never silently present its UDP port as the join port.
 *
 * This only normalizes the NetherNet tunnel type. Java and legacy Bedrock
 * tunnel addresses retain their provider-reported port.
 */
internal object PlayitAddressResolver {
    data class Result(val joinAddress: String, val reportedEndpoint: String? = null)

    private val dnsHostAndPort =
        Regex("""^([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::([0-9]{1,5}))?$""")

    fun resolve(tunnelType: String, displayAddress: String): Result {
        val reported = displayAddress.trim()
        if (reported.isEmpty() || tunnelType != "nethernet") return Result(reported)

        // Do not guess a host from IPv6 literals, URLs or malformed addresses.
        val match = dnsHostAndPort.matchEntire(reported) ?: return Result(reported)
        val host = match.groupValues[1]
        if (!host.contains('.') || host.contains("..")) return Result(reported)
        val suppliedPort = match.groupValues[2]
        if (suppliedPort.isNotEmpty() && suppliedPort.toIntOrNull() !in 1..65535)
            return Result(reported)

        val clientAddress = "$host:19132"
        return Result(clientAddress, reported.takeIf { it != clientAddress })
    }
}
