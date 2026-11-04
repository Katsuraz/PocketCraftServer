package com.pocketcraft.server

import java.io.InterruptedIOException

/** Progress callbacks live on the executing installation worker, never on the UI thread. */
object InstallProgress {
    data class Update(val stage:String,val done:Long=0,val total:Long=-1,val file:Int=0,val files:Int=0)
    private class Session(val callback:(Update)->Unit,val cancelled:()->Boolean) {
        var lastNanos:Long=0
        var lastStage:String=""
        var lastFile:Int=-1
    }
    private val current=ThreadLocal<Session?>()

    fun <T> withSession(callback:(Update)->Unit, cancelled:()->Boolean, work:()->T):T {
        current.set(Session(callback,cancelled))
        try { return work() } finally { current.remove() }
    }
    fun emit(stage:String,done:Long=0,total:Long=-1,file:Int=0,files:Int=0) {
        val session=current.get() ?: return
        if(session.cancelled()) throw InterruptedIOException("Installation cancelled")
        val now=System.nanoTime()
        // Avoid flooding Android's main thread with thousands of callbacks.
        if(stage==session.lastStage && file==session.lastFile &&
            done!=total && now-session.lastNanos<100_000_000L) return
        session.lastNanos=now
        session.lastStage=stage
        session.lastFile=file
        session.callback(Update(stage,done,total,file,files))
    }
    fun check() { if(current.get()?.cancelled?.invoke()==true) throw InterruptedIOException("Installation cancelled") }
}
