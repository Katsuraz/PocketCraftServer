package com.pocketcraft.server

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Search the real Modrinth catalog for Fabric modpacks.
 * A matching Minecraft release is NOT proof of Geyser or Android compatibility.
 */
object ModrinthDiscovery {
    data class Pack(
        val id:String,
        val slug:String,
        val title:String,
        val description:String,
        val gameVersions:List<String>,
        val categories:List<String>,
        val downloads:Long,
        val serverSupport:String
    ) {
        val link:String get() = "https://modrinth.com/modpack/"+slug
        val clientOnly:Boolean get() = serverSupport=="client_only" ||
            serverSupport=="singleplayer_only" || serverSupport=="unsupported"
    }
    data class Page(val packs:List<Pack>,val total:Int,val offset:Int,val limit:Int)
    const val PAGE_SIZE = 16
    private val sorts=setOf("relevance","downloads","updated","newest")
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8")

    private fun request(url:String):HttpURLConnection {
        val parsed=URL(url)
        if(parsed.protocol!="https" ||
            parsed.host !in setOf("api.modrinth.com","cdn.modrinth.com") ||
            parsed.userInfo!=null)throw IOException("Untrusted Modrinth address")
        return (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout=18000
            readTimeout=45000
            instanceFollowRedirects=false
            setRequestProperty("User-Agent","PocketCraftServer/0.8.4 (Minecraft modpack browser)")
            if(responseCode !in 200..299) {
                val code=responseCode
                disconnect()
                throw IOException("Modrinth returned HTTP "+code)
            }
        }
    }

    private fun readJson(url:String):String {
        val c=request(url)
        return try {
            c.inputStream.bufferedReader().use {
                val text=it.readText().take(2_000_000)
                text
            }
        } finally {c.disconnect()}
    }

    private fun strings(values:JSONArray?):List<String> {
        if(values==null)return emptyList()
        return (0 until values.length()).mapNotNull{values.optString(it).takeIf(String::isNotBlank)}
    }

    /** pageOffset is a zero-based hit offset; selecting a version really filters the API. */
    fun find(query:String,gameVersion:String?,sort:String="downloads",pageOffset:Int=0):Page {
        require(sort in sorts){"Unsupported sorting method"}
        require(pageOffset in 0..500){"Too many search results"}
        val filters=JSONArray().apply {
            put(JSONArray().put("project_type:modpack"))
            put(JSONArray().put("categories:fabric"))
            if(!gameVersion.isNullOrBlank()) put(JSONArray().put("versions:"+gameVersion))
        }
        val url="https://api.modrinth.com/v2/search?query="+encode(query.trim().take(80))+
            "&facets="+encode(filters.toString())+
            "&index="+encode(sort)+"&limit="+PAGE_SIZE+"&offset="+pageOffset
        val result=JSONObject(readJson(url))
        val hits=result.getJSONArray("hits")
        val packs=(0 until hits.length()).mapNotNull { i ->
            val item=hits.getJSONObject(i)
            val id=item.optString("project_id")
            val slug=item.optString("slug")
            if(id.isBlank() || !slug.matches(Regex("[A-Za-z0-9_-]{2,120}")))return@mapNotNull null
            val versions=strings(item.optJSONArray("versions"))
            val categories=strings(item.optJSONArray("categories"))
            val environment=item.optString("environment")
                .takeIf{it.isNotBlank()} ?: item.optString("server_side","unknown")
            Pack(id,slug,item.optString("title",slug),
                item.optString("description").take(260),versions,categories,
                item.optLong("downloads",0),environment)
        }
        return Page(packs,result.optInt("total_hits",packs.size),pageOffset,PAGE_SIZE)
    }

    /**
     * Select only an actual Fabric .mrpack release for the exact server game version.
     * The ZIP's dependencies will be checked by ModpackImporter before applying files.
     */
    fun downloadLatest(context:Context,pack:Pack,gameVersion:String):Uri {
        InstallProgress.emit("Finding "+gameVersion+" Fabric release for "+pack.title+"…")
        val data=readJson("https://api.modrinth.com/v2/project/"+encode(pack.id)+
            "/version?loaders="+encode("[\"fabric\"]")+
            "&game_versions="+encode("[\""+gameVersion+"\"]")+
            "&include_changelog=false")
        val versions=JSONArray(data)
        var selected:JSONObject?=null
        for(i in 0 until versions.length()) {
            val version=versions.getJSONObject(i)
            if(version.optString("environment")=="client_only" ||
                version.optString("environment")=="singleplayer_only")continue
            val files=version.optJSONArray("files") ?: continue
            var primary:JSONObject?=null
            for(j in 0 until files.length()) {
                val file=files.getJSONObject(j)
                if(!file.optString("filename").endsWith(".mrpack",true))continue
                if(primary==null || file.optBoolean("primary",false))primary=file
            }
            if(primary!=null) {
                selected=primary
                if(version.optString("version_type")=="release")break
            }
        }
        val file=selected ?: throw IOException(
            "No server-capable Fabric .mrpack release found for Minecraft "+gameVersion+
            ". Choose a different modpack or the correct server version."
        )
        val url=file.optString("url")
        val expected=file.optJSONObject("hashes")?.optString("sha512")?.lowercase(Locale.ROOT)
            ?.takeIf{it.matches(Regex("[a-f0-9]{128}"))}
            ?: throw IOException("Modrinth file is missing its SHA-512 checksum")
        val output=File(context.cacheDir,"modrinth-selected-"+System.nanoTime()+".mrpack")
        val conn=request(url)
        try {
            val md=MessageDigest.getInstance("SHA-512")
            var count=0L
            conn.inputStream.use { input ->
                output.outputStream().use { out ->
                    val buf=ByteArray(32768)
                    while(true) {
                        InstallProgress.check()
                        val n=input.read(buf)
                        if(n<0)break
                        count+=n
                        if(count>150L*1024L*1024L)throw IOException("Modpack archive exceeds 150 MB")
                        md.update(buf,0,n)
                        out.write(buf,0,n)
                        InstallProgress.emit("Downloading "+pack.title,count,conn.contentLengthLong)
                    }
                }
            }
            val actual=md.digest().joinToString(""){"%02x".format(it)}
            if(actual!=expected)throw IOException("Modrinth download checksum mismatch")
            return Uri.fromFile(output)
        } catch(e:Exception) {
            output.delete()
            throw e
        } finally {conn.disconnect()}
    }
}
