package com.pocketcraft.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Release-only, opt-in APK updater. No world files, configs, or Playit secrets are touched.
 * All file/network work must be called from a background thread.
 */
object PocketUpdates {
    private const val REPO = "Katsuraz/PocketCraftServer"
    private const val API = "https://api.github.com/repos/" + REPO + "/releases/latest"
    private const val BETA_API = "https://api.github.com/repos/" + REPO + "/releases?per_page=15"
    private const val MAX_APK = 350L * 1024 * 1024
    private const val CHECK_INTERVAL = 24L * 60 * 60 * 1000

    data class Release(
        val versionCode: Long, val versionName: String, val tag: String,
        val notes: String, val published: String,
        val apkUrl: String, val sha256: String, val size: Long
    )
    data class Download(val file: File, val size: Long)

    fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else
            @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "unknown"

    fun channel(context: Context): String =
        UpdateChannels.normalize(context.getSharedPreferences("pocketcraft",0)
            .getString(UpdateChannels.KEY,UpdateChannels.STABLE))

    fun setChannel(context: Context, channel: String) {
        require(channel in setOf(UpdateChannels.STABLE,UpdateChannels.BETA))
        context.getSharedPreferences("pocketcraft",0).edit()
            .putString(UpdateChannels.KEY,channel)
            .remove("availableUpdate")
            .remove("lastUpdateCheck")
            .apply()
    }

    fun autoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences("pocketcraft",0).getBoolean("autoUpdateCheck",true)

    fun setAutoCheck(context: Context, enabled: Boolean) =
        context.getSharedPreferences("pocketcraft",0).edit()
            .putBoolean("autoUpdateCheck",enabled).apply()

    /** A non-blocking daily check, at most once per 24 hours. */
    fun maybeCheck(context: Context) {
        val prefs = context.getSharedPreferences("pocketcraft",0)
        if (!autoCheckEnabled(context)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastUpdateCheck",0) < CHECK_INTERVAL) return
        prefs.edit().putLong("lastUpdateCheck",now).apply()
        val appContext = context.applicationContext
        Thread {
            runCatching { fetchLatest(channel(appContext)) }.onSuccess { release ->
                prefs.edit().putString("availableUpdate",
                    if (release != null && release.versionCode > installedVersion(appContext))
                        release.versionName else "").apply()
            }
        }.start()
    }

    private fun validDownloadUrl(raw: String): Boolean {
        val uri = Uri.parse(raw)
        return uri.scheme == "https" && uri.host == "github.com" &&
            uri.path?.startsWith("/" + REPO + "/releases/download/") == true
    }

    private fun getHttps(url: String, limit: Int = 200_000): String {
        require(validDownloadUrl(url) || url == API || url == BETA_API) {
            "Untrusted update URL"
        }
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 18000
        connection.setRequestProperty("Accept","application/vnd.github+json")
        connection.setRequestProperty("User-Agent","PocketCraft-Updater")
        try {
            if (connection.responseCode == 404 && url == API) return ""
            if (connection.responseCode !in 200..299) {
                error("GitHub returned HTTP " + connection.responseCode)
            }
            connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var consumed = 0
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    consumed += n
                    require(consumed <= limit) { "Update metadata too large" }
                    output.write(buffer,0,n)
                }
                return output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    /** Stable uses GitHub latest; Beta examines recent prereleases and stable releases. */
    fun fetchLatest(channel: String = UpdateChannels.STABLE): Release? {
        val normalized=UpdateChannels.normalize(channel)
        val releases = if (normalized == UpdateChannels.STABLE) {
            getHttps(API).takeIf { it.isNotBlank() }?.let {
                listOf(JSONObject(it))
            } ?: emptyList()
        } else {
            val data=org.json.JSONArray(getHttps(BETA_API,1_000_000))
            (0 until data.length()).map { data.getJSONObject(it) }
        }
        var best: Release? = null
        for (json in releases) {
            if (json.optBoolean("draft") ||
                !UpdateChannels.accepts(normalized,json.optBoolean("prerelease"))) continue
            // Skip older third-party or incomplete releases from the beta listing,
            // but never accept an unverified artifact.
            val candidate = runCatching { parseVerifiedRelease(json) }.getOrNull()
                ?: continue
            if (best==null || candidate.versionCode > best.versionCode)
                best=candidate
        }
        return best
    }

    private fun parseVerifiedRelease(json: JSONObject): Release {
        val tag = json.getString("tag_name")
        require(tag.startsWith("v")) { "Release tag must start with v" }
        val prerelease=json.optBoolean("prerelease")
        val assets = json.getJSONArray("assets")
        val links = linkedMapOf<String, Pair<String,Long>>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            val url = asset.getString("browser_download_url")
            require(validDownloadUrl(url)) { "Unexpected GitHub release URL" }
            links[name] = url to asset.optLong("size",0)
        }
        val manifestUrl=links["pocketcraft-update.json"]?.first
            ?: error("Missing signed-update metadata")
        val manifest=JSONObject(getHttps(manifestUrl,8192))
        val name=manifest.getString("apkName")
        val apk=links[name] ?: error("Release APK does not match its manifest")
        val hash=manifest.getString("sha256").lowercase(Locale.ROOT)
        require(Regex("[0-9a-f]{64}").matches(hash)) { "Missing valid SHA-256" }
        val versionName=manifest.getString("versionName")
        require(tag=="v"+versionName) { "Release tag and manifest disagree" }
        require(prerelease==UpdateChannels.isBetaVersion(versionName)) {
            "Release prerelease flag and version disagree"
        }
        if(manifest.has("channel"))
            require(manifest.getString("channel")==
                if(prerelease) UpdateChannels.BETA else UpdateChannels.STABLE) {
                "Manifest update channel mismatch"
            }
        val versionCode=manifest.getLong("versionCode")
        require(versionCode>0 && versionName.length<40) { "Invalid release version" }
        require(apk.second in 1..MAX_APK) { "Unexpected APK size" }
        return Release(versionCode,versionName,tag,
            json.optString("body",""),json.optString("published_at",""),
            apk.first,hash,apk.second)
    }

    /** Verify the actual downloaded bytes, followed by Android package metadata/signers. */
    fun download(context: Context, release: Release, progress: (Long,Long)->Unit): Download {
        require(validDownloadUrl(release.apkUrl)) { "Unexpected download URL" }
        val folder = File(context.cacheDir,"pocketcraft-updates").apply { mkdirs() }
        val temp = File(folder,"pocketcraft-download.apk")
        val destination = File(folder,"pocketcraft-verified.apk")
        temp.delete()
        destination.delete()
        val connection = URL(release.apkUrl).openConnection() as HttpsURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (connection.responseCode !in 200..299) {
                error("APK download returned HTTP " + connection.responseCode)
            }
            val contentLength = connection.contentLengthLong
            require(contentLength <= MAX_APK) { "APK exceeds safety limit" }
            connection.inputStream.use { stream ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(65536)
                    var lastUpdate = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_APK) { "APK exceeds safety limit" }
                        digest.update(buffer,0,count)
                        output.write(buffer,0,count)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate > 120) {
                            progress(size,release.size)
                            lastUpdate = now
                        }
                    }
                    output.fd.sync()
                }
            }
            require(size == release.size) { "Downloaded APK size differs from GitHub release" }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(release.sha256,true)) { "APK SHA-256 verification failed" }
            verifyApk(context,temp,release.versionCode)
            require(temp.renameTo(destination)) { "Could not save verified APK" }
            progress(size,size)
            return Download(destination,size)
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally { connection.disconnect() }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map {
                android.util.Base64.encodeToString(it.toByteArray(),android.util.Base64.NO_WRAP)
            }?.toSet() ?: emptySet()
        } else {
            info.signatures?.map {
                android.util.Base64.encodeToString(it.toByteArray(),android.util.Base64.NO_WRAP)
            }?.toSet() ?: emptySet()
        }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File, expectedCode: Long) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath,flags)
            ?: error("Downloaded file is not a valid Android APK")
        require(candidate.packageName == context.packageName) { "APK belongs to another app" }
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else
            candidate.versionCode.toLong()
        require(code == expectedCode && code > installedVersion(context)) {
            "APK version does not match manifest or is not newer"
        }
        val current = pm.getPackageInfo(context.packageName,flags)
        val originalSigners = signatures(current)
        require(originalSigners.isNotEmpty() && originalSigners == signatures(candidate)) {
            "Signing certificate differs from your installed PocketCraft. Android cannot install this as an update. Back up server files before any reinstall."
        }
    }

    fun install(context: Context, file: File) {
        require(file.isFile && file.parentFile?.name == "pocketcraft-updates") {
            "Verified APK not found"
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName)))
            throw IllegalStateException("Enable 'Allow from this source', then press Install again.")
        }
        val uri = FileProvider.getUriForFile(context,
            context.packageName + ".updates",file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri,"application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
