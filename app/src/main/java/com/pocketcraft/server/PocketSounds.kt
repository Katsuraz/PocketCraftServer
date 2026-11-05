package com.pocketcraft.server

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import java.util.EnumMap
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.atomic.AtomicLong

/**
 * Original short WAV effects preloaded in a SoundPool, routed through MEDIA volume.
 *
 * The old AudioTrack implementation used USAGE_ASSISTANCE_SONIFICATION, which
 * can be inaudible on phones where touch sounds are disabled. This version is
 * low-latency and never blocks server startup or UI event handling.
 */
object PocketSounds {
    enum class Event { CLICK, SELECT, SERVER_START, SERVER_READY, SERVER_STOP, ERROR }

    private val guard = Any()
    private val lastClickAt = AtomicLong(0)
    private val samples = EnumMap<Event,Int>(Event::class.java)
    private val ready = HashSet<Int>()
    private val awaiting = HashMap<Int,Pair<Float,Long>>()
    private var pool: SoundPool? = null
    @Volatile private var lastError = ""

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("pocketcraft",0).getBoolean("soundEffects",true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("pocketcraft",0)
            .edit().putBoolean("soundEffects",enabled).apply()
    }

    fun volume(context: Context): Int =
        context.getSharedPreferences("pocketcraft",0)
            .getInt("soundVolume",75).coerceIn(0,100)

    fun setVolume(context: Context, level: Int) {
        context.getSharedPreferences("pocketcraft",0)
            .edit().putInt("soundVolume",level.coerceIn(0,100)).apply()
    }

    /** Warm up sounds asynchronously on application launch. */
    fun prepare(context: Context) {
        synchronized(guard) {
            if (pool != null) return
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val player = SoundPool.Builder()
                    .setMaxStreams(5)
                    .setAudioAttributes(attributes)
                    .build()
                pool = player
                player.setOnLoadCompleteListener { soundPool, id, status ->
                    synchronized(guard) {
                        if (status == 0) {
                            ready.add(id)
                            val waiting = awaiting.remove(id)
                            if (waiting != null &&
                                SystemClock.elapsedRealtime() - waiting.second < 1500) {
                                val stream = soundPool.play(id,waiting.first,waiting.first,1,0,1f)
                                if (stream == 0) lastError = "SoundPool rejected queued playback"
                            }
                        } else {
                            lastError = "Android could not load sound resource (code $status)"
                            awaiting.remove(id)
                        }
                    }
                }
                val resources = mapOf(
                    Event.CLICK to R.raw.pc_click,
                    Event.SELECT to R.raw.pc_select,
                    Event.SERVER_START to R.raw.pc_server_start,
                    Event.SERVER_READY to R.raw.pc_server_ready,
                    Event.SERVER_STOP to R.raw.pc_server_stop,
                    Event.ERROR to R.raw.pc_error
                )
                resources.forEach { (event,resId) ->
                    val id = player.load(context.applicationContext,resId,1)
                    if (id > 0) samples[event] = id
                    else lastError = "Android refused to load $event sound"
                }
            } catch (e: Exception) {
                lastError = "Sound initialization: " + e.javaClass.simpleName +
                    ": " + e.message.orEmpty()
                pool?.release()
                pool = null
                samples.clear()
                ready.clear()
                awaiting.clear()
            }
        }
    }

    /** Disabled and zero-volume settings take effect immediately. */
    fun play(context: Context, event: Event) {
        if (!isEnabled(context)) return
        val percent = volume(context)
        if (percent == 0) return
        if (event == Event.CLICK) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastClickAt.get() < 35L) return
            lastClickAt.set(now)
        }
        prepare(context)
        val level = (percent/100f).coerceIn(0f,1f)
        synchronized(guard) {
            val id = samples[event] ?: return
            val player = pool ?: return
            if (!ready.contains(id)) {
                awaiting[id] = level to SystemClock.elapsedRealtime()
                return
            }
            try {
                val stream = player.play(id,level,level,1,0,1f)
                if (stream == 0) lastError = "SoundPool rejected $event playback"
            } catch (e: Exception) {
                lastError = "Playback: " + e.javaClass.simpleName
            }
        }
    }

    /** Visible, non-secret diagnostics to help when the phone is muted. */
    fun diagnostic(context: Context): String {
        val systemVolume = runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            "Media volume " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) +
                "/" + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault("Media volume unknown")
        val soundInfo = synchronized(guard) {
            "Effects loaded " + ready.size + "/" + samples.size +
                if (lastError.isNotBlank()) " • " + lastError else ""
        }
        return systemVolume + " • " + soundInfo
    }
}
