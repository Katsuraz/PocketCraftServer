package com.pocketcraft.server

import java.net.URI

/**
 * Edits only selected Geyser YAML fields. Everything else (including comments,
 * Floodgate settings and the private NXS token) is retained verbatim.
 *
 * This is intentionally a narrow path-aware editor, not a general YAML parser.
 * Files must be Geyser-generated YAML; importing arbitrary YAML is not supported.
 */
internal object GeyserConfigEditor {
    data class Values(
        val address: String = "0.0.0.0",
        val port: Int = 19132,
        val webRtcPort: Int = 0,
        val transport: String = "nethernet",
        val signalingMode: String = "builtin",
        val nxsEndpoint: String = "https://nethernet.playit.gg",
        val advertiseAddresses: String = "",
        val stunDiscovery: Boolean = false,
        val assistedJoins: Boolean = false,
        val hasSavedNxsToken: Boolean = false
    )

    private data class Entry(val line: Int, val indent: Int, val key: String, val path: List<String>, val raw: String)
    private val keyLine = Regex("^([ ]*)([A-Za-z][A-Za-z0-9_-]*):(?:[ ]*(.*))?$")

    private fun entries(lines: List<String>): List<Entry> {
        val result = mutableListOf<Entry>()
        val parents = mutableListOf<Entry>()
        for ((index, line) in lines.withIndex()) {
            val match = keyLine.matchEntire(line) ?: continue
            val indent = match.groupValues[1].length
            while (parents.isNotEmpty() && parents.last().indent >= indent) parents.removeAt(parents.lastIndex)
            val key = match.groupValues[2]
            val path = parents.map { it.key } + key
            val current = Entry(index, indent, key, path, match.groupValues[3])
            result.add(current)
            parents.add(current)
        }
        return result
    }

    private fun scalar(text: String, path: List<String>): String? =
        entries(text.lines()).lastOrNull { it.path == path }?.raw
            ?.substringBefore(" #")?.trim()?.trim('\'', '"')

    private fun addressList(text: String): String {
        val lines = text.lines()
        val entry = entries(lines).lastOrNull {
            it.path == listOf("bedrock", "signaling", "advertise-addresses")
        } ?: return ""
        val inline = entry.raw.trim()
        if (inline.startsWith("[") && inline.endsWith("]")) {
            return inline.removePrefix("[").removeSuffix("]").split(',')
                .map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }.joinToString(", ")
        }
        return lines.drop(entry.line + 1).takeWhile { line ->
            line.isBlank() || line.length - line.trimStart().length > entry.indent
        }.mapNotNull { line ->
            line.trim().takeIf { it.startsWith("- ") }?.removePrefix("- ")?.trim()?.trim('\'', '"')
        }.joinToString(", ")
    }

    fun read(text: String): Values {
        val lookup: (String) -> String? = { dotted -> scalar(text, dotted.split('.')) }
        return Values(
            address = lookup("bedrock.address") ?: "0.0.0.0",
            port = lookup("bedrock.port")?.toIntOrNull() ?: 19132,
            webRtcPort = lookup("bedrock.webrtc-port")?.toIntOrNull() ?: 0,
            transport = lookup("bedrock.transport") ?: "nethernet",
            signalingMode = lookup("bedrock.signaling.mode") ?: "builtin",
            nxsEndpoint = lookup("bedrock.signaling.nxs.endpoint") ?: "https://nethernet.playit.gg",
            advertiseAddresses = addressList(text),
            stunDiscovery = lookup("bedrock.signaling.stun-discovery").equals("true", true),
            assistedJoins = lookup("advanced.nxs.assisted-joins").equals("true", true),
            hasSavedNxsToken = !lookup("bedrock.signaling.nxs.token").isNullOrBlank()
        )
    }

    /** Returns only non-secret validation results. */
    fun validate(value: Values, replacementToken: String? = null): String? {
        if (value.address.isBlank() || value.address.length > 253 ||
            !Regex("[A-Za-z0-9.:_\\[\\]-]+").matches(value.address))
            return "Enter a valid local bind IP, such as 0.0.0.0"
        if (value.port !in 1..65535) return "Bedrock port must be between 1 and 65535"
        if (value.webRtcPort !in 0..65535) return "WebRTC port must be between 0 and 65535"
        if (value.transport !in setOf("nethernet", "raknet", "both"))
            return "Choose a supported Bedrock transport"
        if (value.signalingMode !in setOf("builtin", "nxs", "hybrid", "none"))
            return "Choose a supported signaling mode"
        if (value.transport == "both" && (value.webRtcPort == 0 || value.webRtcPort == value.port))
            return "Both transports require a different, nonzero WebRTC UDP port"
        val uri = runCatching { URI(value.nxsEndpoint) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null)
            return "NXS endpoint must be a valid HTTPS URL without credentials"
        if (value.advertiseAddresses.length > 2048) return "Advertised addresses are too long"
        for (host in value.advertiseAddresses.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (host.length > 253 || !Regex("[A-Za-z0-9.:_\\[\\]-]+").matches(host))
                return "Advertised addresses must be IPs or hostnames, not URLs"
        }
        if (replacementToken != null && (replacementToken.length > 8192 ||
                    replacementToken.any { it == '\n' || it == '\r' || it.code < 32 }))
            return "Invalid NXS token"
        return null
    }

    private fun quote(value: String) = "'" + value.replace("'", "''") + "'"

    private fun set(lines: MutableList<String>, path: List<String>, encoded: String, isList: Boolean = false) {
        val matches = entries(lines).filter { it.path == path }
        if (matches.isEmpty()) {
            if (path.size == 1) {
                lines.add(path.first() + ": " + encoded)
                return
            }
            val parentPath = path.dropLast(1)
            var parent = entries(lines).firstOrNull { it.path == parentPath }
            if (parent == null) {
                set(lines, parentPath, "")
                parent = entries(lines).first { it.path == parentPath }
            }
            lines.add(parent.line + 1, " ".repeat(parent.indent + 2) + path.last() + ": " + encoded)
            return
        }
        // Remove duplicate keys first, from bottom to top, then replace the first.
        for (entry in matches.drop(1).asReversed()) lines.removeAt(entry.line)
        val first = entries(lines).first { it.path == path }
        if (isList) {
            val start = first.line + 1
            while (start < lines.size) {
                val line = lines[start]
                if (line.isNotBlank() && line.length - line.trimStart().length <= first.indent) break
                lines.removeAt(start)
            }
        }
        lines[first.line] = " ".repeat(first.indent) + first.key + ": " + encoded
    }

    private fun removeMisplacedAdvertiseList(lines: MutableList<String>, path: List<String>) {
        for (entry in entries(lines).filter { it.path == path }.asReversed()) {
            var end = entry.line + 1
            while (end < lines.size) {
                val line = lines[end]
                if (line.isNotBlank() && line.length - line.trimStart().length <= entry.indent) break
                end++
            }
            for (i in (end - 1) downTo entry.line) lines.removeAt(i)
        }
    }

    fun patch(original: String, values: Values, replacementToken: String? = null): String {
        require(original.length <= 128_000 && original.lineSequence().any { it.trim() == "bedrock:" }) {
            "A valid Geyser config.yml must be installed first"
        }
        validate(values, replacementToken)?.let { throw IllegalArgumentException(it) }
        val lines = original.lines().toMutableList()
        // Geyser only recognizes advertised addresses under bedrock.signaling.
        removeMisplacedAdvertiseList(lines, listOf("bedrock", "advertise-addresses"))
        removeMisplacedAdvertiseList(lines, listOf("advertise-addresses"))
        set(lines, listOf("bedrock", "address"), quote(values.address))
        set(lines, listOf("bedrock", "port"), values.port.toString())
        set(lines, listOf("bedrock", "webrtc-port"), values.webRtcPort.toString())
        set(lines, listOf("bedrock", "transport"), values.transport)
        set(lines, listOf("bedrock", "signaling", "mode"), values.signalingMode)
        set(lines, listOf("bedrock", "signaling", "stun-discovery"), values.stunDiscovery.toString())
        val addresses = values.advertiseAddresses.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(lines, listOf("bedrock", "signaling", "advertise-addresses"),
            if (addresses.isEmpty()) "[]" else addresses.joinToString(prefix = "[", postfix = "]", separator = ", ") { quote(it) },
            isList = true)
        set(lines, listOf("bedrock", "signaling", "nxs", "endpoint"), quote(values.nxsEndpoint))
        if (!replacementToken.isNullOrBlank())
            set(lines, listOf("bedrock", "signaling", "nxs", "token"), quote(replacementToken))
        set(lines, listOf("advanced", "nxs", "assisted-joins"), values.assistedJoins.toString())
        val result = lines.joinToString("\n").trimEnd('\n') + "\n"
        // Ensure every managed key occurs exactly once after the patch.
        val resultEntries = entries(result.lines())
        for (path in listOf(
            listOf("bedrock", "address"), listOf("bedrock", "port"),
            listOf("bedrock", "transport"), listOf("bedrock", "signaling", "mode"),
            listOf("bedrock", "signaling", "advertise-addresses")
        )) check(resultEntries.count { it.path == path } == 1)
        return result
    }
}
