package com.pocketcraft.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Read-only Modrinth catalog for individual Fabric mods.
 * Installation reuses WebsiteModInstaller's trusted download and JAR verification.
 */
internal object ModrinthModBrowser {
    const val PAGE_SIZE = 12
    private val sorts = setOf("downloads","relevance","updated","newest")
    private val categories = setOf(
        "optimization","worldgen","utility","decoration","game-mechanics",
        "mobs","equipment","storage","technology","food","adventure","social"
    )

    data class Mod(
        val id:String,
        val title:String,
        val description:String,
        val author:String,
        val downloads:Long,
        val versions:List<String>,
        val clientSide:String,
        val serverSide:String,
        val categories:List<String>
    ) {
        val clientOnly:Boolean get() = serverSide == "unsupported"
        val unknownServerSupport:Boolean get() = serverSide == "unknown"
        val projectUrl:String get() = "https://modrinth.com/mod/"+id
    }

    data class Page(val mods:List<Mod>,val total:Int,val offset:Int)
    data class Release(
        val label:String,
        val number:String,
        val requiredDependencies:Int,
        val optionalDependencies:Int,
        val incompatibleDependencies:Int
    )
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8")

    private fun get(url:String):String {
        val target=URL(url)
        if(target.protocol!="https" || target.host!="api.modrinth.com" ||
            target.userInfo!=null)throw IOException("Untrusted Modrinth API URL")
        val conn=(target.openConnection() as HttpURLConnection).apply {
            connectTimeout=15000
            readTimeout=45000
            instanceFollowRedirects=false
            setRequestProperty("User-Agent","PocketCraftServer/0.8 (Modrinth in-app browser)")
        }
        return try {
            val status=conn.responseCode
            if(status !in 200..299)throw IOException("Modrinth API returned HTTP "+status)
            if(conn.contentLengthLong>2_000_000L)throw IOException("Modrinth response too large")
            conn.inputStream.bufferedReader().use {it.readText().take(2_000_000)}
        } finally {conn.disconnect()}
    }

    private fun strings(list:JSONArray?):List<String> {
        if(list==null)return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            list.optString(i).takeIf{it.isNotBlank()}
        }
    }

    fun search(
        query:String,
        gameVersion:String?,
        category:String?,
        sort:String,
        offset:Int,
        serverOnly:Boolean
    ):Page {
        require(sort in sorts){"Invalid sort option"}
        require(category==null || category in categories){"Invalid mod category"}
        require(offset in 0..240 && offset%PAGE_SIZE==0){"Invalid result page"}
        require(gameVersion==null || gameVersion.matches(Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")))
            {"Invalid Minecraft version"}
        val facets=JSONArray().apply {
            put(JSONArray().put("project_type:mod"))
            put(JSONArray().put("categories:fabric"))
            if(gameVersion!=null)put(JSONArray().put("versions:"+gameVersion))
            if(category!=null)put(JSONArray().put("categories:"+category))
            // Modrinth calls these labels required/optional/unsupported/unknown.
            // Exclude explicitly client-only mods by default.
            if(serverOnly)put(JSONArray().put("server_side:required").put("server_side:optional"))
        }
        val queryText="https://api.modrinth.com/v2/search?query="+
            encode(query.trim().take(90))+"&facets="+encode(facets.toString())+
            "&index="+encode(sort)+"&offset="+offset+"&limit="+PAGE_SIZE
        val page=JSONObject(get(queryText))
        val hits=page.getJSONArray("hits")
        val mods=(0 until hits.length()).mapNotNull { index ->
            val hit=hits.getJSONObject(index)
            val id=hit.optString("project_id")
            if(!id.matches(Regex("[A-Za-z0-9]{3,32}")))return@mapNotNull null
            Mod(
                id,
                hit.optString("title","Untitled mod").take(130),
                hit.optString("description").take(260),
                hit.optString("author","Unknown"),
                hit.optLong("downloads",0),
                strings(hit.optJSONArray("versions")),
                hit.optString("client_side","unknown"),
                hit.optString("server_side","unknown"),
                strings(hit.optJSONArray("categories"))
            )
        }
        return Page(mods,page.optInt("total_hits",mods.size),offset)
    }

    /** Resolve details before installing so client-only and missing releases stay visible. */
    fun release(mod:Mod,gameVersion:String):Release {
        val endpoint="https://api.modrinth.com/v2/project/"+encode(mod.id)+
            "/version?loaders="+encode("[\"fabric\"]")+
            "&game_versions="+encode("[\""+gameVersion+"\"]")
        val versions=JSONArray(get(endpoint))
        for(i in 0 until versions.length()) {
            val entry=versions.getJSONObject(i)
            val files=entry.optJSONArray("files") ?: continue
            val jar=(0 until files.length()).any {j->
                files.getJSONObject(j).optString("filename").endsWith(".jar",true)
            }
            if(!jar)continue
            val dependencies=entry.optJSONArray("dependencies")
            var required=0
            var optional=0
            var incompatible=0
            if(dependencies!=null) {
                for(j in 0 until dependencies.length()) {
                    when(dependencies.getJSONObject(j).optString("dependency_type")) {
                        "required" -> required++
                        "optional" -> optional++
                        "incompatible" -> incompatible++
                    }
                }
            }
            return Release(entry.optString("name",mod.title),
                entry.optString("version_number","unknown"),
                required,optional,incompatible)
        }
        throw IOException("No Fabric JAR release for Minecraft "+gameVersion+". Choose another mod or version.")
    }
}
