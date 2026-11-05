package com.pocketcraft.server

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Saves the most recent uncaught Java/Kotlin exception locally for user-initiated sharing. */
class PocketCraftApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PocketCraftCrashReports.install(this)
    }
}

object PocketCraftCrashReports {
    private const val LIMIT = 16_384
    private const val FILE_NAME = "pocketcraft-last-crash.txt"

    private fun file(c: Context) = File(c.filesDir, FILE_NAME)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, "Uncaught exception on " + thread.name, error)
            } catch (ignored: Throwable) {
                Log.e("PocketCraftCrash", "Could not save crash report", ignored)
            } finally {
                // Always invoke Android's own handler so the process exits normally.
                if (previous != null) previous.uncaughtException(thread, error)
                else android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    fun record(c: Context, event: String, error: Throwable) {
        try { write(c, event, error) }
        catch (ignored: Throwable) {
            Log.e("PocketCraftCrash", "Could not save error report", ignored)
        }
    }

    private fun write(c: Context, label: String, error: Throwable) {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        val version = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "unknown"
        } catch (ignored: Exception) { "unknown" }
        val report = buildString {
            appendLine("PocketCraft crash report • v" + version)
            appendLine("Recorded: " + Date().toString())
            appendLine("Android SDK: " + Build.VERSION.SDK_INT)
            appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Location: " + label)
            appendLine()
            append(writer.toString().take(LIMIT - 512))
        }.take(LIMIT)
        // Local-only private diagnostics; never automatically uploaded.
        val target = file(c)
        val temp = File(c.filesDir, FILE_NAME + ".tmp")
        try {
            temp.writeText(report, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.writeText(report, Charsets.UTF_8)
                temp.delete()
            }
        } finally { temp.delete() }
    }

    fun hasReport(c: Context) = file(c).isFile
    fun read(c: Context): String =
        try { if (file(c).isFile) file(c).readText(Charsets.UTF_8).take(LIMIT)
            else "No PocketCraft Java crash report saved yet." }
        catch (e: Exception) { "Could not read crash report: " + e.message }

    fun clear(c: Context) { file(c).delete() }
}
