package com.pocketcraft.server

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * Experimental Java-only Mindustry dedicated-server installation.
 * All files live outside the existing Minecraft world, mods and configuration.
 */
internal object MindustryHostFiles {
    const val GAME_NAME = "Mindustry"
    const val DEFAULT_PORT = 6567
    const val OFFICIAL_VERSION = "v160.7"
    const val OFFICIAL_URL =
        "https://github.com/Anuken/Mindustry/releases/download/v160.7/server-release.jar"
    // GitHub's digest of Anuken/Mindustry release asset, verified before install.
    private const val OFFICIAL_SHA256 =
        "f7b899a4038b95b14a824d5f8993984f79d73baa3254c385b6538327f9c0befd"
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAIN_CLASS = "mindustry.server.ServerLauncher"

    fun directory(c: Context): File = File(c.filesDir,"game-servers/mindustry").apply { mkdirs() }
    fun serverJar(c: Context) = File(directory(c),"server-release.jar")
    fun log(c: Context) = File(directory(c),"mindustry-console.log")
    fun installed(c: Context): Boolean = serverJar(c).isFile

    /** Verify the JAR's declared server entry point and class before offering Start. */
    fun validateJar(file: File) {
        if (!file.isFile || file.length() !in 1..MAX_BYTES)
            throw IOException("Mindustry server JAR must be 1–64 MB")
        try {
            JarFile(file).use { archive ->
                val main = archive.manifest?.mainAttributes?.getValue("Main-Class")?.trim()
                if (main != MAIN_CLASS)
                    throw IOException("Not a Mindustry dedicated server JAR (unexpected Main-Class)")
                if (archive.getJarEntry("mindustry/server/ServerLauncher.class")==null)
                    throw IOException("Mindustry server launcher class is missing")
            }
        } catch (e:IOException) { throw e }
        catch (e:Exception) { throw IOException("Invalid server JAR: " + e.message,e) }
    }

    private fun checkedInstall(c: Context, input: java.io.InputStream,
                               expectedDigest: String?, progress:(String)->Unit): File {
        if (MindustryHostService.active())
            throw IOException("Stop the Mindustry server before updating its JAR")
        val folder=directory(c)
        val stage=File(folder,".mindustry-installing.jar")
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            var count=0L
            input.use { source ->
                stage.outputStream().buffered().use { target ->
                    val bytes=ByteArray(65536)
                    while(true) {
                        val n=source.read(bytes)
                        if(n<0) break
                        count+=n
                        if(count>MAX_BYTES)throw IOException("Server JAR exceeded 64 MB")
                        digest.update(bytes,0,n)
                        target.write(bytes,0,n)
                        if(count>0L && count%(4L*1024*1024)<bytes.size)
                            progress("Downloaded/imported " + (count/1048576) + " MB")
                    }
                }
            }
            if(count<=0)throw IOException("Empty server JAR")
            val actual=digest.digest().joinToString("") { "%02x".format(it) }
            if(expectedDigest!=null && actual!=expectedDigest)
                throw IOException("Official download checksum mismatch; cancelled")
            validateJar(stage)
            val target=serverJar(c)
            // Always stage; keep the old working JAR when download is interrupted.
            if(target.isFile) {
                val previous=File(folder,"server-previous.jar")
                previous.delete()
                if(!target.renameTo(previous))throw IOException("Unable to protect existing JAR")
                if(!stage.renameTo(target)) {
                    previous.renameTo(target)
                    throw IOException("Could not replace installed JAR")
                }
            } else if(!stage.renameTo(target)) throw IOException("Cannot finish installation")
            return target
        } finally { stage.delete() }
    }

    fun downloadOfficial(c: Context, progress:(String)->Unit): File {
        progress("Downloading official Mindustry " + OFFICIAL_VERSION)
        val con=URL(OFFICIAL_URL).openConnection() as HttpURLConnection
        con.connectTimeout=25000
        con.readTimeout=80000
        con.instanceFollowRedirects=true
        con.setRequestProperty("User-Agent","PocketCraft-experimental-host/1")
        try {
            if(con.responseCode !in 200..299)
                throw IOException("Official Mindustry download HTTP " + con.responseCode)
            return checkedInstall(c,con.inputStream,OFFICIAL_SHA256,progress)
        } finally {con.disconnect()}
    }

    fun importJar(c:Context,uri:Uri,progress:(String)->Unit): File {
        progress("Copying and verifying selected JAR")
        val source=c.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open selected JAR")
        // Manual file import is an explicit user choice, not a verified publisher.
        return checkedInstall(c,source,null,progress)
    }

    fun readTail(c:Context,limit:Int=7000):String {
        val file=log(c)
        if(!file.isFile)return "Mindustry output will appear here after starting."
        return try {
            java.io.RandomAccessFile(file,"r").use { r ->
                val count=minOf(r.length(),limit.toLong()).toInt()
                r.seek(r.length()-count)
                ByteArray(count).also { r.readFully(it) }.toString(Charsets.UTF_8)
            }
        }catch(e:Exception) { "Could not read Mindustry log: " + e.message }
    }
}
