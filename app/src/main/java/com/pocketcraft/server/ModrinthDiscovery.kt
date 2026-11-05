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

/** Read-only Modrinth pack discovery; downloads the selected .mrpack for local import. */
object ModrinthDiscovery {
    data class Pack(val id:String,val title:String,val description:String)
    private fun request(url:String):HttpURLConnection {
        val host=URL(url).host
        if(!url.startsWith("https://") || (host!="api.modrinth.com" && host!="cdn.modrinth.com"))
            throw IOException("Untrusted Modrinth address")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=18000;readTimeout=45000
            setRequestProperty("User-Agent","PocketCraftServer/0.5 (Minecraft server manager)")
            instanceFollowRedirects=true
            if(responseCode !in 200..299)throw IOException("Modrinth returned HTTP $responseCode")
        }
    }
    private fun readJson(url:String):String {
        val c=request(url)
        return try{c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}
    }
    fun find(query:String):List<Pack> {
        val facets=URLEncoder.encode("[[\"project_type:modpack\"],[\"categories:fabric\"]]","UTF-8")
        val text=readJson("https://api.modrinth.com/v2/search?query="+
            URLEncoder.encode(query.take(80),"UTF-8")+"&facets="+facets+"&limit=12")
        val list=JSONObject(text).getJSONArray("hits")
        return (0 until list.length()).map { i ->
            val item=list.getJSONObject(i)
            Pack(item.getString("project_id"),item.optString("title"),item.optString("description").take(130))
        }
    }

    data class Mod(
        val id:String,val slug:String,val title:String,val description:String,
        val serverHint:String
    )
    enum class Verdict { COMPATIBLE, UNSUPPORTED, UNCERTAIN }
    data class Compatibility(
        val verdict: Verdict,
        val title: String,
        val details: String
    )

    /** Search hit environments describe all releases, not necessarily the selected one. */
    private fun serverHint(hit: JSONObject): String {
        val environments = hit.optJSONArray("environment")
        if (environments != null) {
            val values = (0 until environments.length()).map { environments.optString(it) }
            val eligible = values.any {
                it in setOf("client_and_server","server_only","dedicated_server_only",
                    "server_only_client_optional","client_or_server","client_or_server_prefers_both")
            }
            if (eligible) return "Server versions available"
            if (values.isNotEmpty() && values.all {
                it in setOf("client_only","singleplayer_only")
            }) return "Client only"
        }
        return when(hit.optString("server_side")) {
            "unsupported" -> "Client only"
            "required", "optional" -> "Server versions available"
            else -> "Server support: check"
        }
    }
    /** Current game version + Fabric + server-compatible mod search. */
    fun findMods(query:String, gameVersion:String, category:String = "all"):List<Mod> {
        val facets = mutableListOf(
            "[\"project_type:mod\"]",
            "[\"categories:fabric\"]",
            "[\"versions:" + gameVersion.replace(Regex("[^0-9A-Za-z.+_-]"),"") + "\"]"
        )
        val supported = mapOf(
            "performance" to "optimization",
            "worldgen" to "worldgen",
            "utility" to "utility",
            "adventure" to "adventure"
        )
        supported[category]?.let { facets.add("[\"categories:" + it + "\"]") }
        val url="https://api.modrinth.com/v2/search?query="+
            URLEncoder.encode(query.take(80),"UTF-8")+
            "&facets="+URLEncoder.encode("["+facets.joinToString(",")+"]","UTF-8")+
            "&limit=20"
        val list=JSONObject(readJson(url)).getJSONArray("hits")
        return (0 until list.length()).map { i ->
            val item=list.getJSONObject(i)
            Mod(item.optString("project_id"),item.optString("slug"),
                item.optString("title"),item.optString("description").take(110),
                serverHint(item))
        }.filter { it.id.isNotBlank() && it.slug.isNotBlank() }
    }

    /**
     * Check the specific server-version release, not just broad search facets.
     * Matching published metadata cannot prove third-party mod combinations work.
     */
    fun checkCompatibility(projectId: String, gameVersion: String): Compatibility {
        require(projectId.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid Modrinth project" }
        val project = JSONObject(readJson("https://api.modrinth.com/v2/project/" +
            URLEncoder.encode(projectId,"UTF-8")))
        val title = project.optString("title",projectId)
        if (project.optString("project_type") != "mod")
            return Compatibility(Verdict.UNSUPPORTED,title,"This project is not a Fabric mod.")
        val loaders = URLEncoder.encode("[\"fabric\"]","UTF-8")
        val mc = URLEncoder.encode("[\"" + gameVersion + "\"]","UTF-8")
        val result = JSONArray(readJson("https://api.modrinth.com/v2/project/" +
            URLEncoder.encode(projectId,"UTF-8") +
            "/version?loaders=" + loaders + "&game_versions=" + mc + "&include_changelog=false"))
        if (result.length() == 0)
            return Compatibility(Verdict.UNSUPPORTED,title,
                "No release published for Fabric Minecraft " + gameVersion + ".")
        var unverified: Compatibility? = null
        for (i in 0 until result.length()) {
            val version = result.getJSONObject(i)
            val supported = version.optJSONArray("game_versions") ?: JSONArray()
            val platforms = version.optJSONArray("loaders") ?: JSONArray()
            if (!(0 until supported.length()).any { supported.optString(it) == gameVersion } ||
                !(0 until platforms.length()).any { platforms.optString(it) == "fabric" }) continue
            val files = version.optJSONArray("files") ?: JSONArray()
            if (!(0 until files.length()).any { files.optJSONObject(it)
                    ?.optString("filename")?.endsWith(".jar",true) == true }) continue
            val env = version.optString("environment").ifBlank {
                when (project.optString("server_side")) {
                    "unsupported" -> "client_only"
                    "required" -> "server_only"
                    "optional" -> "client_or_server"
                    else -> "unknown"
                }
            }
            if (env in setOf("client_only","singleplayer_only")) continue
            val dependencies = version.optJSONArray("dependencies") ?: JSONArray()
            val required = (0 until dependencies.length()).count {
                dependencies.optJSONObject(it)?.optString("dependency_type") == "required"
            }
            val conflicts = (0 until dependencies.length()).count {
                dependencies.optJSONObject(it)?.optString("dependency_type") == "incompatible"
            }
            val notes = "Fabric " + gameVersion + " • " +
                version.optString("version_number","matching release") +
                "\nRequired dependencies: " + required +
                (if (conflicts > 0) "\nDeclared incompatibilities: " + conflicts else "") +
                "\nPublished metadata only; other mods and Android performance are not verified."
            if (env in setOf("unknown","client_only_server_optional")) {
                unverified = Compatibility(Verdict.UNCERTAIN,title,
                    "Server support needs verification.\n" + notes)
                continue
            }
            return Compatibility(Verdict.COMPATIBLE,title,
                "Compatible with your Fabric server on paper.\n" + notes)
        }
        return unverified ?: Compatibility(Verdict.UNSUPPORTED,title,
            "No downloadable server-compatible Fabric JAR for Minecraft " + gameVersion + ".")
    }

    fun downloadLatest(context:Context,pack:Pack,gameVersion:String):Uri {
        InstallProgress.emit("Finding compatible "+pack.title+" release…")
        val loaders=URLEncoder.encode("[\"fabric\"]","UTF-8")
        val versions=URLEncoder.encode("[\"$gameVersion\"]","UTF-8")
        val data=readJson("https://api.modrinth.com/v2/project/"+pack.id+
            "/version?loaders="+loaders+"&game_versions="+versions)
        val json=JSONArray(data)
        if(json.length()==0)throw IOException("No Fabric modpack release found for Minecraft $gameVersion")
        val files=json.getJSONObject(0).getJSONArray("files")
        var url:String?=null
        for(i in 0 until files.length()) {
            val item=files.getJSONObject(i)
            if(item.optString("filename").endsWith(".mrpack")) {
                url=item.getString("url")
                if(item.optBoolean("primary"))break
            }
        }
        if(url.isNullOrBlank())throw IOException("This Modrinth release has no .mrpack download")
        val output=File(context.cacheDir,"modrinth-selected-"+System.currentTimeMillis()+".mrpack")
        val conn=request(url)
        try {
            var count=0L
            conn.inputStream.use { input ->
                output.outputStream().use { out ->
                    val buf=ByteArray(32768)
                    while(true) {
                        InstallProgress.check()
                        val n=input.read(buf)
                        if(n<0)break
                        count+=n
                        if(count>150L*1024*1024)throw IOException("Modpack archive exceeds 150 MB")
                        out.write(buf,0,n)
                        InstallProgress.emit("Downloading "+pack.title,count,conn.contentLengthLong)
                    }
                }
            }
            return Uri.fromFile(output)
        }catch(e:Exception){output.delete();throw e}
        finally{conn.disconnect()}
    }
}
