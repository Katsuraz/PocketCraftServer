package com.pocketcraft.server

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Reads metadata without loading or executing an untrusted mod JAR. */
object ModInspector {
    data class Info(val title:String,val version:String,val fabric:Boolean)
    fun inspect(jar:File):Info {
        return try {
            ZipFile(jar).use {zip->
                val entry=zip.getEntry("fabric.mod.json") ?: return Info(jar.name.removeSuffix(".disabled"),"Unrecognized JAR",false)
                if(entry.size > 1024*1024) return Info(jar.name,"Metadata too large",false)
                val json=zip.getInputStream(entry).bufferedReader().use { reader->
                    JSONObject(reader.readText().take(1024*1024))
                }
                Info(json.optString("name",json.optString("id",jar.name)),json.optString("version","unknown version"),true)
            }
        } catch(_:Exception) { Info(jar.name.removeSuffix(".disabled"),"Unreadable JAR",false) }
    }
}
