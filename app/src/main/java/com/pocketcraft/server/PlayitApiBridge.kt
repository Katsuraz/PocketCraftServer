package com.pocketcraft.server

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Narrow integration with documented Playit agent API v1.
 * Reads only the local agent's private credential; never sends it anywhere
 * except https://api.playit.gg. Call on a worker thread, not on the UI thread.
 */
internal object PlayitApiBridge {
    data class Snapshot(
        val agentId: String,
        val javaAddress: String?,
        val javaTunnelCount: Int,
        val pendingJavaCount: Int,
        val bedrockAddress: String?,
        val bedrockTunnelCount: Int,
        val pendingBedrockCount: Int,
        val paid: Boolean,
        val bedrockReportedEndpoint: String? = null
    )

    private fun secret(c: Context): String? {
        val file = java.io.File(c.filesDir, "playit/secret.toml")
        if (!file.isFile || file.length() > 4096) return null
        val raw = file.readText().trim()
        val value = if (raw.startsWith("secret_key")) {
            Regex("""(?m)^secret_key\s*=\s*["']([a-fA-F0-9]{32,})["']\s*$""")
                .find(raw)?.groupValues?.get(1)
        } else raw
        return value?.takeIf { it.length in 32..512 && it.length % 2 == 0 &&
            it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }
    }

    fun isClaimed(c: Context): Boolean = secret(c) != null

    private fun call(c: Context, path: String, data: JSONObject): JSONObject {
        val key = secret(c) ?: throw IOException("Connect your Playit account first")
        val url = URL("https://api.playit.gg$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Agent-Key $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw IOException("Playit returned HTTP ${conn.responseCode}")
            val payload = conn.inputStream.use { input ->
                val bytes = ByteArrayOutputStreamBounded.read(input, 1_000_000)
                String(bytes, Charsets.UTF_8)
            }
            val result = JSONObject(payload)
            if (result.optString("status") != "success") {
                val error = result.optString("data").take(160)
                throw IOException("Playit rejected the request: $error")
            }
            return result.optJSONObject("data") ?: JSONObject()
        } finally {
            conn.disconnect()
        }
    }

    fun fetch(c: Context): Snapshot {
        val data = call(c, "/v1/agents/rundata", JSONObject())
        val tunnels = data.optJSONArray("tunnels")
        var address: String? = null
        var javaCount = 0
        var pendingJava = 0
        var bedrockAddress: String? = null
        var bedrockReportedEndpoint: String? = null
        var bedrockCount = 0
        var pendingBedrock = 0
        if (tunnels != null) {
            for (i in 0 until tunnels.length()) {
                val tunnel = tunnels.optJSONObject(i) ?: continue
                if (!tunnel.isNull("disabled_reason")) continue
                val type = tunnel.optString("tunnel_type")
                val addr = tunnel.optString("display_address").trim()
                if (type == "minecraft-java") {
                    javaCount++
                    if (addr.isNotBlank()) address = addr
                } else if (type == "nethernet" || type == "minecraft-bedrock") {
                    bedrockCount++
                    if (addr.isNotBlank()) {
                        val resolved = PlayitAddressResolver.resolve(type, addr)
                        bedrockAddress = resolved.joinAddress
                        bedrockReportedEndpoint = resolved.reportedEndpoint
                    }
                }
            }
        }
        val pending = data.optJSONArray("pending")
        if (pending != null) {
            for (i in 0 until pending.length()) {
                when (pending.optJSONObject(i)?.optString("tunnel_type")) {
                    "minecraft-java" -> pendingJava++
                    "nethernet", "minecraft-bedrock" -> pendingBedrock++
                }
            }
        }
        return Snapshot(
            agentId = data.optString("agent_id"),
            javaAddress = address,
            javaTunnelCount = javaCount,
            pendingJavaCount = pendingJava,
            bedrockAddress = bedrockAddress,
            bedrockTunnelCount = bedrockCount,
            pendingBedrockCount = pendingBedrock,
            paid = data.optJSONObject("permissions")?.optBoolean("has_premium") ?: false,
            bedrockReportedEndpoint = bedrockReportedEndpoint
        )
    }

    /** User-initiated creation of ONE free Java tunnel. Never requests paid tunnel types. */
    fun createJava(c: Context, agentId: String, localPort: Int) {
        if (!agentId.matches(Regex("[0-9a-fA-F-]{36}")))
            throw IOException("Playit did not return an agent identifier")
        if (localPort !in 1..65535) throw IOException("Invalid Minecraft Java port")
        val fields = org.json.JSONArray()
            .put(JSONObject().put("name", "local_ip").put("value", "127.0.0.1"))
            .put(JSONObject().put("name", "local_port").put("value", localPort.toString()))
        val originData = JSONObject().put("agent_id", agentId)
            .put("config", JSONObject().put("fields", fields))
        val request = JSONObject()
            .put("ports", JSONObject().put("type", "tunnel-type").put("details", "minecraft-java"))
            .put("origin", JSONObject().put("type", "agent").put("data", originData))
            .put("enabled", true)
            .put("name", "PocketCraft Java")
            .put("alloc", JSONObject.NULL)
            .put("firewall_id", JSONObject.NULL)
        call(c, "/v1/tunnels/create", request)
    }

    /**
     * Provider-created Minecraft NetherNet tunnel, not a premium custom TCP/UDP port.
     * Playit may reject this if unsupported for the account or agent.
     */
    fun createBedrock(c: Context, agentId: String, localPort: Int) {
        if (!agentId.matches(Regex("[0-9a-fA-F-]{36}")))
            throw IOException("Playit did not return an agent identifier")
        if (localPort !in 1..65535) throw IOException("Invalid Bedrock listener port")
        val fields = org.json.JSONArray()
            .put(JSONObject().put("name", "local_ip").put("value", "127.0.0.1"))
            .put(JSONObject().put("name", "local_port").put("value", localPort.toString()))
        val originData = JSONObject().put("agent_id", agentId)
            .put("config", JSONObject().put("fields", fields))
        val request = JSONObject()
            .put("ports", JSONObject().put("type", "tunnel-type").put("details", "nethernet"))
            .put("origin", JSONObject().put("type", "agent").put("data", originData))
            .put("enabled", true)
            .put("name", "PocketCraft Bedrock")
            .put("alloc", JSONObject.NULL)
            .put("firewall_id", JSONObject.NULL)
        call(c, "/v1/tunnels/create", request)
    }

    private object ByteArrayOutputStreamBounded {
        fun read(input: java.io.InputStream, max: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val bytes = ByteArray(4096)
            while (true) {
                val n = input.read(bytes)
                if (n == -1) break
                if (output.size() + n > max) throw IOException("Playit response too large")
                output.write(bytes, 0, n)
            }
            return output.toByteArray()
        }
    }
}
