package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

class PlayerActivityTest {
    @Test fun tracksJoinsLeavesAndListWithoutChatSpoofing() {
        PlayerActivity.reset()
        PlayerActivity.observe("[Server thread/INFO]: Alex joined the game")
        PlayerActivity.observe("[Server thread/INFO]: .Bedrock joined the game")
        assertEquals(listOf("Alex", ".Bedrock"), PlayerActivity.names())
        PlayerActivity.observe("[Server thread/INFO]: <Steve> Alex left the game")
        assertEquals(2, PlayerActivity.count())
        PlayerActivity.observe("[Server thread/INFO]: Alex left the game")
        assertEquals(listOf(".Bedrock"), PlayerActivity.names())
        PlayerActivity.observe("[Server thread/INFO]: There are 1 of a max of 10 players online: .Bedrock")
        assertEquals(10, PlayerActivity.maxPlayers)
        assertEquals(1, PlayerActivity.count())
        PlayerActivity.reset()
        assertEquals(0, PlayerActivity.count())
    }

    @Test fun modernSystemChatListReconcilesRealPlayers() {
        PlayerActivity.reset()
        PlayerActivity.observe("[18:16:53] [Server thread/INFO]: System chat: There are 2 of a max of 20 players online: Alex, .Bedrock")
        assertEquals(listOf("Alex", ".Bedrock"), PlayerActivity.names())
        assertEquals(20, PlayerActivity.maxPlayers)
        PlayerActivity.observe("[18:16:55] [Server thread/INFO]: System chat: There are 0 of a max of 20 players online:")
        assertEquals(emptyList<String>(), PlayerActivity.names())
        assertEquals(20, PlayerActivity.maxPlayers)
    }

    @Test fun malformedListsAndPlayerChatDoNotOverwriteRoster() {
        PlayerActivity.reset()
        PlayerActivity.observe("[Server thread/INFO]: Alex joined the game")
        PlayerActivity.observe("[Server thread/INFO]: <Steve> There are 0 of a max of 20 players online:")
        PlayerActivity.observe("[Server thread/INFO]: System chat: Alex left the game")
        PlayerActivity.observe("[Server thread/INFO]: There are 2 of a max of 20 players online: Steve")
        PlayerActivity.observe("[Server thread/INFO]: There are 2 of a max of 20 players online: Steve, Steve")
        PlayerActivity.observe("[Server thread/INFO]: There are 0 of a max of 0 players online:")
        PlayerActivity.observe("[Async Chat Thread - #1/INFO]: Alex left the game")
        assertEquals(listOf("Alex"), PlayerActivity.names())
    }
}
