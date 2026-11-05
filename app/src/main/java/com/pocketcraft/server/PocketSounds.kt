package com.pocketcraft.server

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny original synthesized UI sounds. No copyrighted Minecraft audio files.
 * Audio is generated and played off the main thread at the system UI-effects volume.
 */
object PocketSounds {
    enum class Event { CLICK, SELECT, SERVER_START, SERVER_READY, SERVER_STOP, ERROR }
    private const val RATE = 22050
    private val audioThread = Executors.newSingleThreadExecutor { task ->
        Thread(task,"PocketCraft UI sound").apply { isDaemon = true }
    }
    private val lastClick = AtomicLong(0L)
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("pocketcraft",0).getBoolean("soundEffects",true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("pocketcraft",0).edit()
            .putBoolean("soundEffects",enabled).apply()
    }
    fun volume(context: Context): Int =
        context.getSharedPreferences("pocketcraft",0).getInt("soundVolume",45).coerceIn(0,100)
    fun setVolume(context: Context, level: Int) {
        context.getSharedPreferences("pocketcraft",0).edit()
            .putInt("soundVolume",level.coerceIn(0,100)).apply()
    }

    fun play(context: Context, event: Event) {
        if (!isEnabled(context)) return
        val level = volume(context)
        if (level == 0) return
        if (event == Event.CLICK || event == Event.SELECT) {
            val now = SystemClock.elapsedRealtime()
            if (now-lastClick.get() < 45L) return
            lastClick.set(now)
        }
        audioThread.execute {
            try { playOriginalSample(event,level) }
            catch (_: Exception) { /* never interrupt server controls or clicks */ }
        }
    }

    private fun playOriginalSample(event: Event, volume: Int) {
        val length = when(event) {
            Event.CLICK -> 65
            Event.SELECT -> 96
            Event.SERVER_START -> 175
            Event.SERVER_READY -> 450
            Event.SERVER_STOP -> 200
            Event.ERROR -> 260
        }
        val count = RATE * length / 1000
        val data = ShortArray(count)
        val random = Random(9900L + event.ordinal)
        var phase = 0.0
        val gain = volume / 100.0 * 0.29
        for (i in 0 until count) {
            val t = i.toDouble()/RATE
            val progress = i.toDouble()/count
            val envelope = when(event) {
                Event.CLICK -> exp(-37.0*t)
                Event.SELECT -> exp(-25.0*t)
                Event.SERVER_START -> exp(-12.0*t)
                Event.SERVER_READY -> (sin(PI*progress).coerceAtLeast(0.0)*0.85 +
                    (1.0-progress)*0.15)
                Event.SERVER_STOP -> exp(-13.0*t)
                Event.ERROR -> exp(-12.0*t)
            }
            val pitch = when(event) {
                Event.CLICK -> 920.0-580.0*progress
                Event.SELECT -> 630.0-200.0*progress
                Event.SERVER_START -> 390.0+330.0*progress
                Event.SERVER_READY -> when {
                    progress < .28 -> 523.25
                    progress < .55 -> 659.25
                    else -> 783.99
                }
                Event.SERVER_STOP -> 490.0-280.0*progress
                Event.ERROR -> 260.0-115.0*progress
            }
            phase += 2.0*PI*pitch/RATE
            // Wood-like attack: a few harmonics plus gently damped percussive noise.
            val noise = (random.nextDouble()*2.0-1.0) * exp(-65.0*t) *
                if(event==Event.SERVER_READY) 0.06 else 0.34
            val fundamental = sin(phase)*0.75 + sin(phase*1.99)*0.18 +
                sin(phase*3.02)*0.07
            val v = ((fundamental+noise)*envelope*gain)
                .coerceIn(-0.95,0.95)
            data[i] = (v*Short.MAX_VALUE).toInt().toShort()
        }
        val bytes = ByteArray(count*2)
        for(i in data.indices) {
            val sample = data[i].toInt()
            bytes[2*i] = (sample and 0xff).toByte()
            bytes[2*i+1] = ((sample shr 8) and 0xff).toByte()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes.size).build()
        try {
            if(track.state == AudioTrack.STATE_INITIALIZED) {
                track.write(bytes,0,bytes.size)
                track.play()
                Thread.sleep(length.toLong()+30)
            }
        } finally { track.release() }
    }
}
