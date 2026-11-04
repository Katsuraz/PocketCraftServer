package com.pocketcraft.server

import android.content.Context
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Opt-in Android ARM64 JRE installer. Uses an upstream Android-specific OpenJDK archive,
 * with a pinned SHA-256; does not bundle or silently execute third-party components.
 * Runtime execution is experimental and must be tested on an actual Android device.
 */
object RuntimeStore {
    const val VERSION = 25
    private const val ARCHIVE_URL =
        "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/jre25-android-arm64.tar.xz"
    // SHA-256 published by the upstream release in August 2026.
    private const val ARCHIVE_SHA256 = "d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37"
    private const val LIMIT_DOWNLOAD = 100L * 1024 * 1024
    private const val LIMIT_EXTRACT = 850L * 1024 * 1024

    fun root(c: Context): File = File(c.filesDir, "runtime25")

    fun javaFile(c: Context): File? {
        val dir = root(c)
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .maxDepth(5)
            .firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
    }
    fun isInstalled(c: Context): Boolean = javaFile(c) != null
    fun availableForDevice(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun install(c: Context, progress: (String) -> Unit): String {
        if (!availableForDevice()) throw IOException("Java 25 runtime requires an ARM64 Android device")
        val archive = File(c.cacheDir, "jre25-android-arm64.tar.xz")
        try {
            progress("Downloading Java 25 (about 36 MB)")
            InstallProgress.emit("Downloading Java 25…")
            val conn = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 25000
                conn.readTimeout = 90000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "PocketCraftServer/0.3")
                if (conn.responseCode !in 200..299) throw IOException("Java download returned HTTP ${conn.responseCode}")
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                conn.inputStream.use { input ->
                    archive.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            length += count
                            InstallProgress.emit("Downloading Java 25",length,conn.contentLengthLong)
                            if (length > LIMIT_DOWNLOAD) throw IOException("Java archive exceeded 100 MB limit")
                            digest.update(buffer, 0, count)
                            out.write(buffer, 0, count)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != ARCHIVE_SHA256) throw IOException("Java archive checksum mismatch; installation cancelled")
            } finally { conn.disconnect() }
            progress("Verifying and unpacking Android Java 25")
            InstallProgress.emit("Verifying and extracting Java 25…")
            unpack(c, archive)
            return "Java 25 files installed. Launch support is experimental: use Console to check JVM output."
        } finally { archive.delete() }
    }

    /** Actually starts java -version, so a detected file is not mistaken for a working JVM. */
    fun probe(c: Context): String {
        val java = javaFile(c) ?: throw IOException("Install Android Java first")
        val javaHome = java.parentFile!!.parentFile!!
        val process = ProcessBuilder(java.absolutePath, "-version")
            .directory(c.filesDir)
            .redirectErrorStream(true)
            .apply {
                val env = environment()
                env["JAVA_HOME"] = javaHome.absolutePath
                env["TMPDIR"] = c.cacheDir.absolutePath
                env["LD_LIBRARY_PATH"] = listOf(
                    File(javaHome, "lib").absolutePath,
                    File(javaHome, "lib/server").absolutePath,
                    c.applicationInfo.nativeLibraryDir,
                    env["LD_LIBRARY_PATH"].orEmpty()
                ).filter { it.isNotBlank() }.joinToString(":")
            }.start()
        val text = process.inputStream.bufferedReader().use { it.readText().take(7000) }
        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Java self-test timed out")
        }
        if (process.exitValue() != 0) throw IOException("Java self-test failed: " + text.take(450))
        return text.trim().ifEmpty { "Java binary exited successfully without version output" }
    }

    private fun unpack(c: Context, archive: File) {
        val target = root(c)
        val stage = File(c.filesDir, "runtime25-installing")
        stage.deleteRecursively()
        if (!stage.mkdir()) throw IOException("Unable to create runtime installer directory")
        var total = 0L
        var entries = 0
        try {
            FileInputStream(archive).use { raw ->
                XZCompressorInputStream(raw).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            val item = tar.nextTarEntry ?: break
                            InstallProgress.check()
                            if (++entries > 12000) throw IOException("Too many JRE archive entries")
                            val name = ArchiveSafety.relativeEntry(item.name) ?: continue
                            val file = ArchiveSafety.within(stage, name)
                            when {
                                item.isDirectory -> if (!file.mkdirs() && !file.isDirectory) throw IOException("Could not create directory")
                                item.isSymbolicLink -> {
                                    file.parentFile?.mkdirs()
                                    ArchiveSafety.checkedSymlinkTarget(stage, file.parentFile!!, item.linkName)
                                    Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(item.linkName))
                                }
                                item.isFile -> {
                                    file.parentFile?.mkdirs()
                                    file.outputStream().use { out ->
                                        val buffer = ByteArray(65536)
                                        while (true) {
                                            val n = tar.read(buffer)
                                            if (n < 0) break
                                            total += n
                                            InstallProgress.emit("Unpacking Java runtime",total,-1)
                                            if (total > LIMIT_EXTRACT) throw IOException("JRE unpacked size too large")
                                            out.write(buffer, 0, n)
                                        }
                                    }
                                    if (item.mode and 0x49 != 0) file.setExecutable(true, true)
                                }
                                else -> throw IOException("Unsupported JRE archive entry type")
                            }
                        }
                    }
                }
            }
            val java = stage.walkTopDown().maxDepth(5)
                .firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
                ?: throw IOException("The downloaded archive does not contain bin/java; runtime needs an Android launcher adapter")
            java.setExecutable(true, true)
            val old = File(c.filesDir, "runtime25-previous")
            old.deleteRecursively()
            if (target.exists() && !target.renameTo(old)) throw IOException("Could not preserve previous runtime")
            if (!stage.renameTo(target)) {
                if (old.exists()) old.renameTo(target)
                throw IOException("Could not activate downloaded runtime")
            }
            old.deleteRecursively()
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e
        }
    }
}
