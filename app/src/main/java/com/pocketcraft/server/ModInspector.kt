package com.pocketcraft.server

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Reads Fabric metadata without executing an untrusted mod JAR. */
object ModInspector {
    data class Info(
        val title:String,
        val version:String,
        val fabric:Boolean,
        val modId:String?=null,
        val requiredDependencies:Set<String> = emptySet(),
        val provides:Set<String> = emptySet()
    )

    fun inspect(jar:File):Info {
        return try {
            ZipFile(jar).use {zip->
                val entry=zip.getEntry("fabric.mod.json")
                    ?: return Info(jar.name.removeSuffix(".disabled"),"Unrecognized JAR",false)
                if(entry.size > 1024*1024) return Info(jar.name,"Metadata too large",false)
                val json=zip.getInputStream(entry).bufferedReader().use { reader->
                    JSONObject(reader.readText().take(1024*1024))
                }
                val id=json.optString("id","").takeIf { it.matches(Regex("[a-z0-9_.-]{1,80}")) }
                val depends=json.optJSONObject("depends")
                val required=linkedSetOf<String>()
                if(depends!=null) {
                    val keys=depends.keys()
                    var n=0
                    while(keys.hasNext() && n<100) {
                        n++
                        val dependency=keys.next()
                        if(dependency.matches(Regex("[a-z0-9_.-]{1,80}")))
                            required.add(dependency)
                    }
                }
                val provides=linkedSetOf<String>()
                val declared=json.optJSONArray("provides")
                if(declared!=null)for(i in 0 until minOf(declared.length(),100)) {
                    val value=declared.optString(i,"")
                    if(value.matches(Regex("[a-z0-9_.-]{1,80}"))) provides.add(value)
                }
                Info(json.optString("name",json.optString("id",jar.name)),
                    json.optString("version","unknown version"),true,id,required,provides)
            }
        } catch(_:Exception) { Info(jar.name.removeSuffix(".disabled"),"Unreadable JAR",false) }
    }
}
