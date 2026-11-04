package com.pocketcraft.server

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Identifies the installed Fabric server version without inferring it from an
 * unrelated modpack selection. Imported JARs may not have app version metadata.
 */
internal object ServerVersionManager {
    data class Info(val version:String?,val source:String,val launcherPresent:Boolean)

    private val known=Regex("""^[0-9]+\.[0-9]+(?:\.[0-9]+)?$""")
    private val loaderLine=Regex(
        """Loading Minecraft\s+([0-9]+\.[0-9]+(?:\.[0-9]+)?)\s+with Fabric Loader""",
        RegexOption.IGNORE_CASE
    )

    fun fromLog(text:String):String? = loaderLine.findAll(text).lastOrNull()
        ?.groupValues?.get(1)

    fun current(context:Context):Info {
        val launcher=File(WorldStore.root(context),"fabric-server-launch.jar")
        if(!launcher.isFile)return Info(null,"Fabric server launcher missing",false)
        val prefs=context.getSharedPreferences("pocketcraft",0)
        val saved=prefs.getString("gameVersion",null)
            ?.takeIf{known.matches(it)}
        // The real Java console is more authoritative than a stale preference,
        // but only if it was written after the current launcher was installed.
        val console=File(WorldStore.root(context),"console.log")
        val observed=if(console.isFile && console.lastModified()>=launcher.lastModified()) {
            runCatching {
                RandomAccessFile(console,"r").use {file->
                    val count=minOf(file.length(),131_072L).toInt()
                    file.seek(file.length()-count)
                    val bytes=ByteArray(count)
                    file.readFully(bytes)
                    fromLog(String(bytes,Charsets.UTF_8))
                }
            }.getOrNull()
        } else null
        if(observed!=null) {
            if(observed!=saved)prefs.edit().putString("gameVersion",observed).apply()
            return Info(observed,"detected in Fabric server console",true)
        }
        return if(saved!=null) Info(saved,"saved in PocketCraft",true)
            else Info(null,"Fabric installed; Minecraft version unknown",true)
    }

    fun confirm(context:Context,version:String) {
        if(!File(WorldStore.root(context),"fabric-server-launch.jar").isFile)
            throw IOException("Install or import the Fabric server launcher first")
        if(!known.matches(version))throw IOException("Invalid Minecraft version")
        context.getSharedPreferences("pocketcraft",0).edit().putString("gameVersion",version).apply()
    }

    fun requireMatching(context:Context,required:String) {
        val current=current(context)
        if(!current.launcherPresent)
            throw IOException("Fabric server launcher is missing. Install or import Fabric first.")
        val actual=current.version ?: throw IOException(
            "Fabric is installed but its Minecraft version is unknown. In Mods, tap CONFIRM EXISTING FABRIC VERSION and select the version your server actually uses. Do not reinstall the server just to fix this message."
        )
        if(actual!=required)
            throw IOException("This modpack requires Minecraft $required, but your Fabric server is $actual. Choose a release matching your server; never mix Minecraft versions.")
    }
}
