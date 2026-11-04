package com.pocketcraft.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarFile
import java.util.zip.ZipFile

/** Curated vanilla-client-compatible server addons, not automatic modpack conversion. */
internal object CrossplayAddons {
    private const val MC = "26.3"
    private const val FILE_LIMIT = 90L * 1024L * 1024L

    enum class Preset(val title:String,val description:String) {
        PERFORMANCE("Crossplay Performance", "Lithium + FerriteCore: improve server performance without requiring client mods."),
        TERRALITH("Fantasy Biomes", "Terralith: new Overworld biomes with vanilla blocks."),
        TECTONIC("Epic Mountains", "Tectonic: dramatic vanilla-block Overworld terrain."),
        NULLSCAPE("Beyond the End", "Nullscape: expanded End, only for worlds whose End is not yet generated.")
    }
    private data class Addon(val project:String,val loader:String,val filename:String)
    private fun encode(s:String)=URLEncoder.encode(s,"UTF-8")

    private fun checkServer(context:Context) {
        if (ServerService.running || ServerService.status=="STARTING" || ServerService.status=="STOPPING") {
            throw IOException("Stop Minecraft before installing addons")
        }
        val installed=context.getSharedPreferences("pocketcraft",0).getString("gameVersion",null)
        if (installed!=MC) throw IOException("Geyser crossplay presets require Minecraft "+MC+
            "; current server: "+(installed ?: "not installed"))
    }

    private fun connection(address:String):HttpURLConnection {
        val uri=URL(address)
        if(uri.protocol!="https" || uri.host !in setOf("api.modrinth.com","cdn.modrinth.com") || uri.userInfo!=null) {
            throw IOException("Untrusted Modrinth URL")
        }
        return (uri.openConnection() as HttpURLConnection).apply {
            connectTimeout=15000
            readTimeout=60000
            instanceFollowRedirects=false
            setRequestProperty("User-Agent","PocketCraftServer/0.8 (crossplay)")
            if(responseCode !in 200..299) {
                val status=responseCode
                disconnect()
                throw IOException("Modrinth HTTP "+status)
            }
        }
    }

    private fun resolve(addon:Addon):JSONObject {
        val endpoint="https://api.modrinth.com/v2/project/"+encode(addon.project)+
            "/version?loaders="+encode("[\""+addon.loader+"\"]")+
            "&game_versions="+encode("[\""+MC+"\"]")
        val request=connection(endpoint)
        try {
            val results=JSONArray(request.inputStream.bufferedReader().use {it.readText().take(2_000_000)})
            for(i in 0 until results.length()) {
                val files=results.getJSONObject(i).optJSONArray("files") ?: continue
                for(j in 0 until files.length()) {
                    val file=files.getJSONObject(j)
                    val suffix=if(addon.loader=="datapack")".zip" else ".jar"
                    if(file.optString("filename").endsWith(suffix,true))return file
                }
            }
            throw IOException("No "+addon.loader+" file available for "+addon.project+" on "+MC)
        } finally {request.disconnect()}
    }

    private fun download(addon:Addon,staged:File) {
        InstallProgress.emit("Checking "+addon.project+" compatibility…")
        val file=resolve(addon)
        val expected=file.optJSONObject("hashes")?.optString("sha256")
            ?.takeIf {it.matches(Regex("[a-fA-F0-9]{64}"))}
            ?: throw IOException("Modrinth SHA-256 is missing for "+addon.project)
        val request=connection(file.optString("url"))
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            var bytes=0L
            request.inputStream.use {input->
                staged.outputStream().use {output->
                    val chunk=ByteArray(32768)
                    while(true) {
                        InstallProgress.check()
                        val count=input.read(chunk)
                        if(count<0)break
                        bytes+=count
                        if(bytes>FILE_LIMIT)throw IOException("Addon exceeds 90 MB")
                        digest.update(chunk,0,count)
                        output.write(chunk,0,count)
                        InstallProgress.emit("Downloading "+addon.project,bytes,request.contentLengthLong)
                    }
                }
            }
            val sha=digest.digest().joinToString(""){"%02x".format(it)}
            if(!sha.equals(expected,true))throw IOException("Checksum mismatch for "+addon.project)
            if(addon.loader=="datapack") {
                ZipFile(staged).use {zip->
                    if(zip.getEntry("pack.mcmeta")==null)throw IOException("Invalid datapack ZIP")
                }
            } else {
                JarFile(staged).use {jar->
                    if(jar.getJarEntry("fabric.mod.json")==null)throw IOException("Not a Fabric mod JAR")
                }
            }
            InstallProgress.emit("Verified "+addon.project,1,1)
        } finally {request.disconnect()}
    }

    private fun modInstalled(dir:File,project:String):Boolean {
        val key=project.replace(Regex("[^A-Za-z0-9]"),"").lowercase(Locale.ROOT)
        return dir.listFiles()?.any {
            it.isFile && it.name.endsWith(".jar",true) &&
                it.name.replace(Regex("[^A-Za-z0-9]"),"").lowercase(Locale.ROOT).contains(key)
        } == true
    }

    private fun installMods(context:Context,selected:List<Addon>):String {
        val destination=File(WorldStore.root(context),"mods").apply{mkdirs()}
        val missing=selected.filter{!modInstalled(destination,it.project)}
        if(missing.isEmpty())return "All addons are already installed. Restart if needed."
        val work=File(context.cacheDir,"pocketcraft-addons-"+System.nanoTime())
        if(!work.mkdirs())throw IOException("Cannot stage addons")
        val committed=mutableListOf<File>()
        try {
            missing.forEach{download(it,File(work,it.filename))}
            missing.forEach {
                InstallProgress.check()
                val target=File(destination,it.filename)
                if(target.exists())throw IOException("Addon target already exists: "+target.name)
                if(!File(work,it.filename).renameTo(target))throw IOException("Cannot save "+target.name)
                committed.add(target)
            }
            return "Installed "+missing.joinToString(", "){it.project}+". Restart Minecraft."
        } catch(e:Exception) {
            committed.forEach{it.delete()}
            throw e
        } finally {work.deleteRecursively()}
    }

    private fun activeWorld(context:Context):File {
        val root=WorldStore.root(context).canonicalFile
        val world=File(root,WorldStore.active(context)).canonicalFile
        if(world.parentFile!=root || !File(world,"level.dat").isFile)
            throw IOException("Start the world once, stop the server, then install worldgen addons")
        return world
    }

    private fun installDatapack(context:Context,addon:Addon):String {
        val world=activeWorld(context)
        val folder=File(world,"datapacks")
        val current=folder.listFiles()?.filter{it.isFile && it.name.endsWith(".zip",true)} ?: emptyList()
        if(current.any{it.name.equals(addon.filename,true)})
            return addon.project+" is already installed"
        if(addon.project=="terralith" && current.any{it.name.contains("tectonic",true)} ||
            addon.project=="tectonic" && current.any{it.name.contains("terralith",true)}) {
            throw IOException("Terralith and Tectonic 26.3 are separate presets. Do not combine their datapacks.")
        }
        if(addon.project=="nullscape" && (File(world,"DIM1").exists() ||
            File(world,"dimensions/minecraft/the_end").exists())) {
            throw IOException("The End already exists. Nullscape should be used before generating the End.")
        }
        val staged=File(context.cacheDir,"pocketcraft-datapack-"+System.nanoTime()+".zip")
        try {
            download(addon,staged)
            InstallProgress.emit("Creating a world recovery snapshot…")
            WorldSafety.snapshot(context,world)
            if(!folder.isDirectory && !folder.mkdirs())throw IOException("Cannot create datapacks directory")
            val target=File(folder,addon.filename)
            if(target.exists() || !staged.renameTo(target))throw IOException("Could not install datapack")
            return "Installed "+addon.project+" in "+world.name+". World backup saved. Restart server; new terrain generates in unexplored chunks."
        } finally {staged.delete()}
    }

    fun install(context:Context,preset:Preset):String {
        checkServer(context)
        return when(preset) {
            Preset.PERFORMANCE -> installMods(context,listOf(
                Addon("lithium","fabric","pocketcraft-lithium.jar"),
                Addon("ferrite-core","fabric","pocketcraft-ferritecore.jar")))
            Preset.TERRALITH -> installDatapack(context,Addon("terralith","datapack","pocketcraft-terralith.zip"))
            Preset.TECTONIC -> installDatapack(context,Addon("tectonic","datapack","pocketcraft-tectonic.zip"))
            Preset.NULLSCAPE -> installDatapack(context,Addon("nullscape","datapack","pocketcraft-nullscape.zip"))
        }
    }

    fun installDistantHorizons(context:Context):String {
        checkServer(context)
        return installMods(context,listOf(
            Addon("distanthorizons","fabric","pocketcraft-distanthorizons.jar"))) +
            "\nJava players must also install Distant Horizons locally. Bedrock visuals will not change; server LOD generation may use extra phone CPU and storage."
    }
}
