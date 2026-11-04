package com.pocketcraft.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.jar.JarFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Properties

/** Official downloads; no JAR is executed by this app yet. Call from a background thread. */
object ServerDownloads {
    private const val FABRIC_META = "https://meta.fabricmc.net/v2/versions"
    private const val GEYSER_DOWNLOADS = "https://download.geysermc.org/v2/projects/"
    private const val MAX_JAR_BYTES = 100L * 1024 * 1024

    private fun encoded(s: String) = URLEncoder.encode(s,"UTF-8")
    private fun connection(url: String): HttpURLConnection {
        require(url.startsWith("https://"))
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 45000
            setRequestProperty("User-Agent","PocketCraftServer/0.2 (Android)")
            instanceFollowRedirects = true
            connect()
            if (responseCode !in 200..299) throw IOException("Download returned HTTP "+responseCode)
        }
    }
    private fun readJsonArray(url: String): JSONArray {
        val c = connection(url)
        return try {
            val text = c.inputStream.bufferedReader().use {it.readText()}
            JSONArray(text)
        } finally {c.disconnect()}
    }
    private fun latestStable(array: JSONArray): String {
        for(i in 0 until array.length()) {
            val item=array.getJSONObject(i)
            if(item.optBoolean("stable",false)) return item.getString("version")
        }
        throw IOException("No stable versions published")
    }
    private fun download(url: String, destination: File, expectedSha256: String? = null) {
        val temp = File(destination.parentFile,destination.name+".part")
        InstallProgress.emit("Connecting to download server…")
        val c=connection(url)
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            if(c.contentLengthLong > MAX_JAR_BYTES) throw IOException("File exceeds safe size limit")
            var bytes=0L
            c.inputStream.use {input ->
                temp.outputStream().use {output ->
                    val buffer=ByteArray(32768)
                    while(true) {
                        val n=input.read(buffer)
                        if(n<0)break
                        bytes+=n
                        digest.update(buffer,0,n)
                        InstallProgress.emit("Downloading "+destination.name,bytes,c.contentLengthLong)
                        if(bytes>MAX_JAR_BYTES) throw IOException("Download exceeded safe size limit")
                        output.write(buffer,0,n)
                    }
                }
            }
            InstallProgress.emit("Verifying "+destination.name)
            val actual=digest.digest().joinToString("") { "%02x".format(it) }
            if(expectedSha256 != null && !actual.equals(expectedSha256,ignoreCase=true))
                throw IOException("SHA-256 mismatch for "+destination.name+" (download corrupted)")
            try {
                JarFile(temp).use { jar ->
                    if(!jar.entries().hasMoreElements()) throw IOException("Downloaded JAR is empty")
                }
            } catch(e: Exception) {
                throw IOException("Invalid JAR returned for "+destination.name+": "+e.message,e)
            }
            val old=File(destination.parentFile,destination.name+".old")
            if(destination.exists()) {
                old.delete()
                if(!destination.renameTo(old)) throw IOException("Could not replace the previous JAR")
            }
            if(!temp.renameTo(destination)) {
                if(old.exists()) old.renameTo(destination)
                throw IOException("Could not install downloaded JAR")
            }
            old.delete()
            InstallProgress.emit("Installed "+destination.name,1,1)
        } finally {c.disconnect();temp.delete()}
    }

    fun installFabric(context: Context, version: String): String {
        if (ServerService.running) throw IOException("Stop the Minecraft server before changing its version")
        if(version !in listOf("26.3","1.21.11","1.20.1"))
            throw IOException("Unsupported version selection")
        InstallProgress.emit("Checking Fabric versions…")
        val loader=latestStable(readJsonArray(FABRIC_META+"/loader"))
        val installer=latestStable(readJsonArray(FABRIC_META+"/installer"))
        val url=FABRIC_META+"/loader/"+encoded(version)+"/"+encoded(loader)+"/"+encoded(installer)+"/server/jar"
        val base=WorldStore.root(context)
        val currentVersion=context.getSharedPreferences("pocketcraft",0)
            .getString("gameVersion",null)
        if(currentVersion!=null && currentVersion!=version) {
            // Minecraft version changes can make existing world data unusable.
            // Back up the active world before replacing the server launcher.
            val world=File(base,WorldStore.active(context))
            if(File(world,"level.dat").isFile) {
                InstallProgress.emit("Saving world recovery snapshot…")
                WorldSafety.snapshot(context,world)
            }
        }
        download(url,File(base,"fabric-server-launch.jar"))
        context.getSharedPreferences("pocketcraft",0).edit().putString("gameVersion",version).apply()
        val props=File(base,"server.properties")
        if(!props.exists()) {
            val values=Properties().apply {
                setProperty("motd","PocketCraft Server")
                setProperty("online-mode","true")
                setProperty("max-players","5")
                setProperty("view-distance","5")
                setProperty("simulation-distance","4")
            }
            props.outputStream().use {values.store(it,"PocketCraft defaults")}
        }
        return "Fabric "+version+" launcher downloaded (loader "+loader+"). Java runtime is still required."
    }

    private fun readJsonObject(url: String): JSONObject {
        val c = connection(url)
        return try {
            val body = c.inputStream.bufferedReader().use { it.readText().take(1_000_000) }
            JSONObject(body)
        } finally { c.disconnect() }
    }

    private data class CrossplayFile(val url: String, val sha256: String?)

    /** Resolves the Fabric-specific artifact from the GeyserMC build metadata. */
    private fun geyserFabric(project: String): CrossplayFile {
        val base = GEYSER_DOWNLOADS + project + "/versions/latest/builds/latest"
        val info = readJsonObject(base)
        val version = info.getString("version")
        val build = info.getInt("build")
        val file = info.optJSONObject("downloads")?.optJSONObject("fabric")
            ?: throw IOException("GeyserMC " + project + " latest build has no Fabric download")
        val name = file.getString("name")
        if (!name.endsWith(".jar", ignoreCase=true))
            throw IOException("Unexpected " + project + " artifact name: " + name)
        val sha = file.optString("sha256").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            ?: throw IOException("GeyserMC " + project + " metadata has no valid SHA-256")
        val url = GEYSER_DOWNLOADS + project + "/versions/" +
            encoded(version) + "/builds/" + build + "/downloads/fabric"
        return CrossplayFile(url, sha)
    }

    /** Resolve one project release explicitly compatible with Fabric 26.3. */
    private fun modrinthFabric(project: String): CrossplayFile {
        val loaders = encoded("[\"fabric\"]")
        val versions = encoded("[\"26.3\"]")
        val list = readJsonArray("https://api.modrinth.com/v2/project/" + encoded(project) +
            "/version?loaders=" + loaders + "&game_versions=" + versions)
        if (list.length() == 0) throw IOException("Modrinth has no Fabric 26.3 version for " + project)
        for (v in 0 until list.length()) {
            val files = list.getJSONObject(v).optJSONArray("files") ?: continue
            var selected: JSONObject? = null
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                if (!file.optString("filename").endsWith(".jar", true)) continue
                if (selected == null || file.optBoolean("primary",false)) selected = file
            }
            if (selected != null) {
                val sha = selected.optJSONObject("hashes")?.optString("sha256")
                    ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                val url = selected.getString("url")
                if (!url.startsWith("https://")) throw IOException("Modrinth returned an unsafe download URL")
                return CrossplayFile(url,sha)
            }
        }
        throw IOException("Modrinth " + project + " has no Fabric 26.3 JAR")
    }

    private fun fabricApi26_3(): CrossplayFile = modrinthFabric("P7dR8mSH")

    /**
     * Floodgate's latest GeyserMC build can have no Fabric artifact (its build
     * metadata is not guaranteed to include one). Resolve the official 26.3
     * Fabric release from Modrinth instead, with a SHA-256 checksum.
     */
    private fun floodgateFabric(): CrossplayFile {
        return try {
            geyserFabric("floodgate")
        } catch (metadataError: Exception) {
            try {
                modrinthFabric("floodgate")
            } catch (modrinthError: Exception) {
                throw IOException("GeyserMC: " + metadataError.message +
                    "; Modrinth fallback: " + modrinthError.message,modrinthError)
            }
        }
    }

    fun installCrossplay(context: Context): String {
        if (ServerService.running) throw IOException("Stop Minecraft before changing its mods")
        val version = context.getSharedPreferences("pocketcraft",0).getString("gameVersion",null)
        if (version != "26.3")
            throw IOException("Automatic Geyser-Fabric installation requires Minecraft 26.3; selected: " +
                (version ?: "none") + ". Install the 26.3 Fabric launcher first.")

        // Resolve all three remote artifacts before touching installed mods.
        val targets = listOf("fabric-api.jar","Geyser-Fabric.jar","floodgate-fabric.jar")
        val mods = File(WorldStore.root(context),"mods").apply { mkdirs() }
        val stage = File(mods,".crossplay-installing")
        stage.deleteRecursively()
        if (!stage.mkdirs()) throw IOException("Unable to create crossplay staging directory")
        try {
            val downloads = listOf(
                "Fabric API" to { fabricApi26_3() },
                "Geyser-Fabric" to { geyserFabric("geyser") },
                "Floodgate-Fabric" to { floodgateFabric() }
            )
            downloads.forEachIndexed { i, entry ->
                InstallProgress.check()
                InstallProgress.emit("Resolving "+entry.first+"…")
                try {
                    val source = entry.second.invoke()
                    InstallProgress.emit("Downloading "+entry.first+"…")
                    download(source.url, File(stage,targets[i]),source.sha256)
                } catch (e: Exception) {
                    throw IOException(entry.first+" installation failed: "+(e.message ?: e.javaClass.simpleName),e)
                }
            }
            // Commit only when every artifact has downloaded and passed validation.
            // On errors, restore previously installed files instead of leaving a
            // half-updated mod set behind.
            val backups = File(stage,"backups").apply { mkdirs() }
            val committed = mutableListOf<Int>()
            try {
                targets.forEachIndexed { i, name ->
                    val output = File(mods,name)
                    val old = File(backups,name)
                    if (output.exists() && !output.renameTo(old))
                        throw IOException("Could not back up existing "+name)
                    if (!File(stage,name).renameTo(output)) {
                        if(old.exists())old.renameTo(output)
                        throw IOException("Could not install downloaded "+name)
                    }
                    committed.add(i)
                }
            } catch (e: Exception) {
                committed.reversed().forEach { i ->
                    val output = File(mods,targets[i])
                    val old = File(backups,targets[i])
                    output.delete()
                    if(old.exists())old.renameTo(output)
                }
                throw e
            }
            return "Minecraft 26.3 crossplay files installed: Fabric API, Geyser-Fabric, Floodgate. Start the server, then confirm the Geyser listener on port 19132."
        } finally {
            stage.deleteRecursively()
        }
    }

    fun acceptEula(context:Context) {
        File(WorldStore.root(context),"eula.txt").writeText("eula=true\n")
    }
    fun eulaAccepted(context:Context):Boolean =
        File(WorldStore.root(context),"eula.txt").takeIf {it.isFile}
            ?.readText()?.lineSequence()?.any {it.trim()=="eula=true"} == true
}
