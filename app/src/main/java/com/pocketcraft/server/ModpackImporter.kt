package com.pocketcraft.server

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Installs server-compatible content from a user-supplied modpack archive. */
object ModpackImporter {
    data class Summary(val name:String,val platform:String,val version:String,val loader:String,val count:Int,val skipped:Int) {
        fun description():String = "$name ($platform)\nMinecraft $version • $loader\nInstalled $count file(s); skipped $skipped client/optional file(s). Restart the server. Confirm crossplay compatibility separately."
    }
    private const val FILE_LIMIT = 120L * 1024 * 1024
    private const val PACK_LIMIT = 700L * 1024 * 1024
    private const val MAX_FILES = 2000

    private fun safePath(raw:String):String {
        if (raw.isEmpty() || raw.startsWith("/") || raw.contains('\\') ||
            raw.contains(':') || raw.contains('\u0000')) throw IOException("Unsafe modpack path")
        val chunks=raw.split('/')
        if (chunks.any { it==".." || it=="." || it.isEmpty() }) throw IOException("Unsafe modpack path: $raw")
        return chunks.joinToString("/")
    }
    private fun allowed(path:String):Boolean =
        (path.startsWith("mods/") && path.endsWith(".jar",true)) ||
        path.startsWith("config/") ||
        path.startsWith("defaultconfigs/") ||
        path.startsWith("kubejs/") ||
        path.startsWith("datapacks/")

    private fun dest(root:File,path:String):File {
        val safe=safePath(path)
        if(!allowed(safe))throw IOException("Unsupported server modpack path: $path")
        val file=File(root,safe)
        if(!file.canonicalPath.startsWith(root.canonicalPath+File.separator))
            throw IOException("Modpack attempted to escape server directory")
        return file
    }

    private fun http(url:String,key:String?=null):HttpURLConnection {
        if (!url.startsWith("https://")) throw IOException("Only HTTPS mod downloads allowed")
        val host=URL(url).host.lowercase()
        val allowed=host=="cdn.modrinth.com" || host=="github.com" ||
            host=="raw.githubusercontent.com" || host=="gitlab.com" ||
            host=="api.curseforge.com" || host=="edge.forgecdn.net" ||
            host.endsWith(".forgecdn.net")
        if(!allowed)throw IOException("Untrusted mod download host: $host")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=20000;readTimeout=60000
            setRequestProperty("User-Agent","PocketCraftServer/0.5 (Android)")
            if(!key.isNullOrBlank() && (host=="api.curseforge.com" || host.endsWith(".forgecdn.net"))) {
                setRequestProperty("x-api-key",key)
            }
            instanceFollowRedirects=true
            if(responseCode !in 200..299) throw IOException("Download HTTP $responseCode: $host")
        }
    }

    private fun download(url:String,output:File,sha512:String?,key:String?,
                         fileNo:Int,totalFiles:Int):Long {
        val conn=http(url,key)
        val md=if(!sha512.isNullOrBlank()) MessageDigest.getInstance("SHA-512") else null
        val temporary=File(output.parentFile,output.name+".part")
        try {
            var bytes=0L
            conn.inputStream.use { input ->
                temporary.outputStream().use { stream ->
                    val buffer=ByteArray(32768)
                    while(true) {
                        InstallProgress.check()
                        val n=input.read(buffer)
                        if(n<0)break
                        bytes+=n
                        if(bytes>FILE_LIMIT)throw IOException("Mod file exceeds 120 MB limit")
                        md?.update(buffer,0,n)
                        stream.write(buffer,0,n)
                        InstallProgress.emit(output.name,bytes,conn.contentLengthLong,fileNo,totalFiles)
                    }
                }
            }
            if(md!=null) {
                val hash=md.digest().joinToString(""){"%02x".format(it)}
                if(!hash.equals(sha512,true)) throw IOException("SHA-512 mismatch for "+output.name)
            }
            if(!temporary.renameTo(output))throw IOException("Cannot save "+output.name)
            return bytes
        }finally{conn.disconnect();temporary.delete()}
    }

    private fun json(url:String,key:String):JSONObject {
        val c=http(url,key)
        return try { JSONObject(c.inputStream.bufferedReader().use {it.readText()}) }
        finally { c.disconnect() }
    }

    private fun stageOverrides(zip:ZipFile,stage:File,prefix:String):Int {
        var count=0
        var bytes=0L
        val entries=zip.entries()
        while(entries.hasMoreElements()) {
            InstallProgress.check()
            val entry=entries.nextElement()
            if(entry.isDirectory || !entry.name.startsWith(prefix))continue
            val relative=entry.name.removePrefix(prefix)
            if(relative.isEmpty() || !allowed(relative))continue
            val output=dest(stage,relative)
            output.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                output.outputStream().use { out ->
                    val buf=ByteArray(32768)
                    while(true) {
                        val n=input.read(buf)
                        if(n<0)break
                        bytes+=n
                        if(bytes>PACK_LIMIT)throw IOException("Modpack overrides exceed 700 MB")
                        out.write(buf,0,n)
                    }
                }
            }
            if(++count>MAX_FILES)throw IOException("Too many override files")
        }
        return count
    }

    private fun apply(stage:File,root:File):Int {
        val files=stage.walkTopDown().filter{it.isFile}.toList()
        if(files.size>MAX_FILES)throw IOException("Pack contains too many files")
        // Never modify server.properties, EULA, launchers, or world folder.
        files.forEach {file ->
            val relative=file.relativeTo(stage).invariantSeparatorsPath
            val target=dest(root,relative)
            if(target.exists())throw IOException("File already exists: $relative. Remove it or choose a fresh server.")
        }
        files.forEach {file ->
            InstallProgress.check()
            val relative=file.relativeTo(stage).invariantSeparatorsPath
            val target=dest(root,relative)
            target.parentFile?.mkdirs()
            file.copyTo(target,overwrite=false)
        }
        return files.size
    }

    fun install(context:Context,uri:Uri,apiKey:String?=null):Summary {
        if(ServerService.running)throw IOException("Stop the server before importing a modpack")
        val base=WorldStore.root(context)
        val staging=File(context.cacheDir,"pocketcraft-pack-"+System.currentTimeMillis())
        if(!staging.mkdir())throw IOException("Cannot create temporary installation folder")
        val archive=File(staging,"pack.zip")
        val extracted=File(staging,"files").apply{mkdirs()}
        try {
            InstallProgress.emit("Reading modpack archive…")
            var archiveBytes=0L
            (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open archive")).use { input ->
                archive.outputStream().use { out ->
                    val buf=ByteArray(32768)
                    while(true) {
                        InstallProgress.check()
                        val n=input.read(buf)
                        if(n<0)break
                        archiveBytes+=n
                        if(archiveBytes>PACK_LIMIT)throw IOException("Modpack archive larger than 700 MB")
                        out.write(buf,0,n)
                    }
                }
            }
            ZipFile(archive).use { zip ->
                val mr=zip.getEntry("modrinth.index.json")
                val cf=zip.getEntry("manifest.json")
                return when {
                    mr!=null -> installMrpack(context,zip,mr,extracted,base)
                    cf!=null -> installCurse(context,zip,cf,extracted,base,apiKey)
                    else -> throw IOException("No modrinth.index.json or CurseForge manifest.json found")
                }
            }
        } finally {staging.deleteRecursively()}
    }

    private fun checkVersion(context:Context,required:String) {
        val current=context.getSharedPreferences("pocketcraft",0).getString("gameVersion",null)
        if(current.isNullOrBlank())throw IOException("Install the matching Fabric server version before importing a modpack ($required).")
        if(current!=required)throw IOException("Modpack requires Minecraft $required, but the current Fabric server is $current. Switch versions before importing.")
    }

    private fun installMrpack(context:Context,zip:ZipFile,manifest:java.util.zip.ZipEntry,stage:File,base:File):Summary {
        val index=JSONObject(zip.getInputStream(manifest).bufferedReader().use{it.readText()})
        if(index.optString("game")!="minecraft")throw IOException("Only Minecraft modpacks are supported")
        val deps=index.getJSONObject("dependencies")
        val version=deps.optString("minecraft")
        val loader=deps.optString("fabric-loader")
        if(loader.isBlank())throw IOException("This pack is not Fabric. Forge/NeoForge/Quilt cannot run in PocketCraft Fabric.")
        checkVersion(context,version)
        val files=index.getJSONArray("files")
        if(files.length()>MAX_FILES)throw IOException("Too many modpack files")
        var skipped=0
        for(i in 0 until files.length()) {
            InstallProgress.check()
            val item=files.getJSONObject(i)
            val path=safePath(item.getString("path"))
            val side=item.optJSONObject("env")?.optString("server","required") ?: "required"
            if(side=="unsupported" || side=="optional" || !allowed(path)){skipped++;continue}
            val urls=item.getJSONArray("downloads")
            if(urls.length()==0)throw IOException("No download URL for $path")
            val output=dest(stage,path)
            output.parentFile?.mkdirs()
            download(urls.getString(0),output,item.optJSONObject("hashes")?.optString("sha512"),null,i+1,files.length())
        }
        InstallProgress.emit("Unpacking server overrides…")
        stageOverrides(zip,stage,"overrides/")
        stageOverrides(zip,stage,"server-overrides/")
        InstallProgress.emit("Applying modpack files…")
        val installed=apply(stage,base)
        return Summary(index.optString("name","Modrinth pack"),"Modrinth",version,"Fabric $loader",installed,skipped)
    }

    private fun installCurse(context:Context,zip:ZipFile,manifest:java.util.zip.ZipEntry,stage:File,base:File,key:String?):Summary {
        val index=JSONObject(zip.getInputStream(manifest).bufferedReader().use{it.readText()})
        if(index.optString("manifestType")!="minecraftModpack") throw IOException("Not a CurseForge Minecraft modpack")
        val mc=index.getJSONObject("minecraft")
        val version=mc.optString("version")
        val loaders=mc.getJSONArray("modLoaders")
        var loader=""
        for(i in 0 until loaders.length()) {
            val id=loaders.getJSONObject(i).optString("id")
            if(id.startsWith("fabric-")) {loader=id;break}
        }
        if(loader.isBlank())throw IOException("Only Fabric CurseForge modpacks are supported")
        checkVersion(context,version)
        val files=index.getJSONArray("files")
        if(files.length()>MAX_FILES)throw IOException("Too many CurseForge mods")
        if(files.length()>0 && key.isNullOrBlank())
            throw IOException("CurseForge requires an approved developer API key for mod downloads. Enter your key when importing.")
        var skipped=0
        for(i in 0 until files.length()) {
            InstallProgress.check()
            val item=files.getJSONObject(i)
            if(!item.optBoolean("required",true)){skipped++;continue}
            val project=item.getLong("projectID")
            val id=item.getLong("fileID")
            InstallProgress.emit("Resolving CurseForge file ${i+1}/${files.length()}…",0,-1,i+1,files.length())
            val data=json("https://api.curseforge.com/v1/mods/$project/files/$id",key!!).getJSONObject("data")
            val filename=data.getString("fileName")
            if(!filename.endsWith(".jar",true)){skipped++;continue}
            val output=dest(stage,"mods/"+safePath(filename))
            output.parentFile?.mkdirs()
            val endpoint="https://api.curseforge.com/v1/mods/$project/files/$id/download-url"
            val url=json(endpoint,key).optString("data")
            if(url.isBlank() || url=="null")throw IOException("CurseForge restricts downloading $filename; obtain a server pack manually.")
            download(url,output,null,key,i+1,files.length())
        }
        InstallProgress.emit("Unpacking server overrides…")
        stageOverrides(zip,stage,index.optString("overrides","overrides")+"/")
        InstallProgress.emit("Applying modpack files…")
        val installed=apply(stage,base)
        return Summary(index.optString("name","CurseForge pack"),"CurseForge",version,loader,installed,skipped)
    }
}
