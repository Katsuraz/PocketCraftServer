package com.pocketcraft.server

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

/** Phone-level telemetry. This intentionally does not claim to measure JVM TPS or heap usage. */
internal object ServerVitals {
    fun uptime(): String {
        val started=ServerService.startedElapsedMs
        if(!ServerService.running || started<=0L)return "Offline"
        val seconds=((SystemClock.elapsedRealtime()-started)/1000L).coerceAtLeast(0L)
        val hours=seconds/3600
        val mins=(seconds%3600)/60
        return if(hours>0) "${hours}h ${mins}m" else "${mins}m ${seconds%60}s"
    }
    fun device(c:Context): String {
        val am=c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info=ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val available=String.format(Locale.ROOT,"%.1f",info.availMem.toDouble()/1073741824.0)
        val freeBytes=c.filesDir.usableSpace.coerceAtLeast(0L)
        val freeStorage=String.format(Locale.ROOT,"%.1f",freeBytes.toDouble()/1073741824.0)
        val storageState=if(freeBytes < 512L*1024*1024) " · LOW SPACE" else ""
        val battery=c.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
        val percent=if(level>=0 && scale>0) (level*100/scale).toString()+"%" else "unknown"
        val pm=c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal=if(Build.VERSION.SDK_INT>=29)when(pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        } else "Unavailable"
        return "Phone RAM free: ${available} GB  •  Battery: ${percent}\nStorage free: ${freeStorage} GB${storageState}\nThermal: ${thermal}  •  Uptime: ${uptime()}"
    }
}
