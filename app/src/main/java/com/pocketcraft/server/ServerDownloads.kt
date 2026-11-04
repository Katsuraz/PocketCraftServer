package com.pocketcraft.server

import android.content.Context
import org.json.JSONArray
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
    private fun download(url: String, destination: File) {
        val temp = File(destination.parentFile,destination.name+".part")
        val c=connection(url)
        try {
            if(c.contentLengthLong > MAX_JAR_BYTES) throw IOException("File exceeds safe size limit")
            var bytes=0L
            c.inputStream.use {input ->
                temp.outputStream().use {output ->
                    val buffer=ByteArray(32768)
                    while(true) {
                        val n=input.read(buffer)
                        if(n<0)break
                        bytes+=n
                        if(bytes>MAX_JAR_BYTES) throw IOException("Download exceeded safe size limit")
                        output.write(buffer,0,n)
                    }
                }
            }
            temp.inputStream().use {
                val magic=ByteArray(2)
                if(it.read(magic)!=2 || magic[0]!=0x50.toByte() || magic[1]!=0x4B.toByte())
                    throw IOException("The server returned a file that is not a JAR")
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
        } finally {c.disconnect();temp.delete()}
    }

    fun installFabric(context: Context, version: String): String {
        if (ServerService.running) throw IOException("Stop the Minecraft server before changing its version")
        if(version !in listOf("26.3","1.21.11","1.20.1"))
            throw IOException("Unsupported version selection")
        val loader=latestStable(readJsonArray(FABRIC_META+"/loader"))
        val installer=latestStable(readJsonArray(FABRIC_META+"/installer"))
        val url=FABRIC_META+"/loader/"+encoded(version)+"/"+encoded(loader)+"/"+encoded(installer)+"/server/jar"
        val base=WorldStore.root(context)
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

    fun installCrossplay(context: Context): String {
        if (ServerService.running) throw IOException("Stop the Minecraft server before updating mods")
        val version=context.getSharedPreferences("pocketcraft",0).getString("gameVersion",null)
        if(version!="26.3") throw IOException("Auto-install supports only Java 26.3. Older Fabric needs a compatible proxy.")
        val mods=File(WorldStore.root(context),"mods").apply {mkdirs()}
        val loaders=encoded("[\"fabric\"]")
        val versions=encoded("[\"26.3\"]")
        val apiVersions=readJsonArray("https://api.modrinth.com/v2/project/P7dR8mSH/version?loaders="+loaders+"&game_versions="+versions)
        if(apiVersions.length()==0) throw IOException("No Fabric API version was found for Minecraft 26.3")
        val candidates=apiVersions.getJSONObject(0).getJSONArray("files")
        if(candidates.length()==0) throw IOException("Fabric API has no downloadable files")
        var file=candidates.getJSONObject(0)
        for(i in 0 until candidates.length()) {
            val candidate=candidates.getJSONObject(i)
            if(candidate.optBoolean("primary")) {file=candidate;break}
        }
        download(file.getString("url"),File(mods,"fabric-api.jar"))
        download(GEYSER_DOWNLOADS+"geyser/versions/latest/builds/latest/downloads/fabric",
            File(mods,"Geyser-Fabric.jar"))
        download(GEYSER_DOWNLOADS+"floodgate/versions/latest/builds/latest/downloads/fabric",
            File(mods,"floodgate-fabric.jar"))
        return "Fabric API, Geyser-Fabric, and Floodgate downloaded. Crossplay still needs a compatible Android JVM and server test."
    }

    fun acceptEula(context:Context) {
        File(WorldStore.root(context),"eula.txt").writeText("eula=true\n")
    }
    fun eulaAccepted(context:Context):Boolean =
        File(WorldStore.root(context),"eula.txt").takeIf {it.isFile}
            ?.readText()?.lineSequence()?.any {it.trim()=="eula=true"} == true
}
