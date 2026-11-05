package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCommandHintsTest {
    @Test fun discoveredVanillaAndFabricCommandsAreSearchable() {
        ServerCommandHints.reset()
        ServerCommandHints.observe("[13:14:15] [Server thread/INFO]: /help")
        ServerCommandHints.observe("[13:14:15] [Server thread/INFO]: /list")
        ServerCommandHints.observe("[13:14:15] [Server thread/INFO]: /chunky start")
        ServerCommandHints.observe("[13:14:15] [Server thread/INFO]: /chunky radius <radius>")
        assertTrue(ServerCommandHints.registered().contains("chunky"))
        assertTrue(ServerCommandHints.suggestions("chun").contains("chunky"))
        assertTrue(ServerCommandHints.suggestions("/chunky s").contains("chunky start"))
        assertEquals(3, ServerCommandHints.registeredCount())
    }

    @Test fun modernSystemChatHelpRegistersCommandsWithoutGeneratingMoreHelp() {
        ServerCommandHints.reset()
        ServerCommandHints.observe("[18:16:52] [Server thread/INFO]: System chat: /geyser [connectiontest|dump|help|list]")
        ServerCommandHints.observe("[18:16:52] [Server thread/INFO]: System chat: /chunky [start|pause|cancel|progress]")
        ServerCommandHints.observe("[18:16:52] [Server thread/INFO]: System chat: /gamerule (keep_inventory|pvp)")
        assertTrue(ServerCommandHints.registered().contains("geyser"))
        assertTrue(ServerCommandHints.registered().contains("chunky"))
        assertTrue(ServerCommandHints.registered().contains("gamerule"))
        assertTrue(ServerCommandHints.suggestions("gey").contains("geyser"))
    }

    @Test fun unrelatedLogAndAddressesDoNotCreateCommands() {
        ServerCommandHints.reset()
        ServerCommandHints.observe("[Server thread/INFO]: Connecting https://example.com/path")
        ServerCommandHints.observe("[Server thread/INFO]: java.io.FileNotFoundException /tmp/no-file")
        ServerCommandHints.observe("[Server thread/INFO]: Join at host.example/secret")
        assertEquals(0, ServerCommandHints.registeredCount())
        assertFalse(ServerCommandHints.suggestions("secret").isNotEmpty())
    }

    @Test fun vanillaHelpPagesAreDetectedAndReset() {
        ServerCommandHints.reset()
        ServerCommandHints.observe("[16:00:00] [Server thread/INFO]: -------- Help: Index (1/7) --------")
        assertEquals(7, ServerCommandHints.helpPageCount)
        ServerCommandHints.observe("[16:00:01] [Server thread/INFO]: /chunky start")
        assertTrue(ServerCommandHints.registered().contains("chunky"))
        ServerCommandHints.reset()
        assertEquals(1, ServerCommandHints.helpPageCount)
    }

    @Test fun textualHelpPaginationIsBounded() {
        ServerCommandHints.reset()
        ServerCommandHints.observe("[Server thread/INFO]: Showing help page 1 of 300")
        assertEquals(24, ServerCommandHints.helpPageCount)
    }

    @Test fun resetClearsPreviousWorldModCommands() {
        ServerCommandHints.reset()
        ServerCommandHints.observe("[Server thread/INFO]: Usage: /some_mod:teleport home")
        assertTrue(ServerCommandHints.registered().contains("some_mod:teleport"))
        ServerCommandHints.reset()
        assertTrue(ServerCommandHints.registered().isEmpty())
    }

    @Test fun suggestionDoesNotSubmitCommandsAndHasUsefulOfflineFallbacks() {
        ServerCommandHints.reset()
        assertTrue(ServerCommandHints.suggestions("").contains("help"))
        assertTrue(ServerCommandHints.suggestions("ti").contains("time set day"))
    }
}
