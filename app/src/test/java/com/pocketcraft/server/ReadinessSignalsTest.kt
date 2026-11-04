package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessSignalsTest {
    @Test fun javaReadinessOnlyAfterDoneMessage() {
        val r=ReadinessSignals()
        r.observe("[Server thread/INFO]: Preparing spawn area")
        assertFalse(r.javaReady)
        r.observe("[Server thread/INFO]: Done (5.73s)! For help, type \"help\"")
        assertTrue(r.javaReady)
        assertEquals("JAVA READY",r.state())
    }

    @Test fun geyserListenerIsDifferentFromJavaStartup() {
        val r=ReadinessSignals()
        r.observe("[Geyser-Fabric] Loading resources")
        assertFalse(r.bedrockListener)
        r.observe("[Geyser-Fabric] Started Geyser on 0.0.0.0:19132")
        assertTrue(r.bedrockListener)
        assertEquals("STARTING",r.state())
        r.observe("[Server thread/INFO]: Done (11.8s)! For help, type \"help\"")
        assertEquals("JAVA + BEDROCK READY",r.state())
        r.reset()
        assertFalse(r.javaReady)
        assertFalse(r.bedrockListener)
    }
}
