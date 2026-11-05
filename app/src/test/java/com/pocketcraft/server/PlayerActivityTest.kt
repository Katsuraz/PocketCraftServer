package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

class PlayerActivityTest {
    @Test fun tracksJoinsLeavesAndListWithoutChatSpoofing() {
        PlayerActivity.reset()
        PlayerActivity.observe("[Server thread/INFO]: Alex joined the game")
        PlayerActivity.observe("[Server thread/INFO]: .Bedrock joined the game")
        assertEquals(listOf("Alex",".Bedrock"),PlayerActivity.names())
        PlayerActivity.observe("[Server thread/INFO]: <Steve> Alex left the game")
        assertEquals(2,PlayerActivity.count())
        PlayerActivity.observe("[Server thread/INFO]: Alex left the game")
        assertEquals(listOf(".Bedrock"),PlayerActivity.names())
        PlayerActivity.observe("[Server thread/INFO]: There are 1 of a max of 10 players online: .Bedrock")
        assertEquals(10,PlayerActivity.maxPlayers)
        assertEquals(1,PlayerActivity.count())
        PlayerActivity.reset()
        assertEquals(0,PlayerActivity.count())
    }
}
