package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCommandActivityTest {
    @Test fun recordsOwnCommandsAndNearbyReplies() {
        ConsoleCommandActivity.reset()
        ConsoleCommandActivity.queued("list", 1000)
        ConsoleCommandActivity.delivered()
        ConsoleCommandActivity.observe(
            "[18:16:53] [Server thread/INFO]: System chat: There are 2 of a max of 5 players online: Alex, Steve",
            1500
        )
        val snapshot = ConsoleCommandActivity.snapshot()
        assertTrue(snapshot.contains("› list"))
        assertTrue(snapshot.contains("✓ Written to Minecraft stdin"))
        assertTrue(snapshot.contains("There are 2 of a max of 5"))
    }

    @Test fun ignoresHelpDumpButPreservesErrorMessages() {
        ConsoleCommandActivity.reset()
        ConsoleCommandActivity.queued("list", 1000)
        ConsoleCommandActivity.observe(
            "[18:16:52] [Server thread/INFO]: System chat: /gamerule (keep_inventory|pvp|mob_griefing)",
            1400
        )
        ConsoleCommandActivity.observe(
            "[18:16:52] [Server thread/INFO]: System chat: Chunky Commands",
            1401
        )
        ConsoleCommandActivity.observe("[18:16:52] [Server thread/INFO]: System chat: chunky start - Generate chunks", 1402)
        ConsoleCommandActivity.observe("[18:16:52] [Server thread/WARN]: Unknown command", 1403)
        val transcript=ConsoleCommandActivity.snapshot()
        assertFalse(transcript.contains("/gamerule"))
        assertFalse(transcript.contains("Chunky Commands"))
        assertFalse(transcript.contains("chunky start"))
        assertTrue(transcript.contains("Unknown command"))
    }

    @Test fun onlyCapturesDuringCurrentWindowAndResets() {
        ConsoleCommandActivity.reset()
        ConsoleCommandActivity.observe("[1] [Server thread/INFO]: System chat: hi", 1000)
        assertTrue(ConsoleCommandActivity.snapshot().contains("No commands"))
        ConsoleCommandActivity.queued("seed", 1000)
        ConsoleCommandActivity.observe("[1] [Server thread/INFO]: System chat: Seed: [1]", 22001)
        assertFalse(ConsoleCommandActivity.snapshot().contains("Seed:"))
        ConsoleCommandActivity.reset()
        assertFalse(ConsoleCommandActivity.snapshot().contains("› seed"))
    }

    @Test fun helpDetectionPreservesOrdinaryReplies() {
        assertTrue(ConsoleCommandActivity.isHelpListing(
            "[18:16:52] [Server thread/INFO]: System chat: /effect (clear|give)"))
        assertTrue(ConsoleCommandActivity.isHelpListing(
            "[PocketCraft] chunky start - Start a new chunk generation task"))
        assertFalse(ConsoleCommandActivity.isHelpListing(
            "[18:16:52] [Server thread/INFO]: System chat: Set the time to 1000"))
    }

    @Test fun transcriptIsBounded() {
        ConsoleCommandActivity.reset()
        for(i in 1..80)ConsoleCommandActivity.queued("say " + i, 1000L+i)
        val snapshot=ConsoleCommandActivity.snapshot()
        assertTrue(snapshot.contains("› say 80"))
        assertFalse(snapshot.contains("› say 1\n"))
        assertEquals(35,snapshot.lines().size)
    }
}
