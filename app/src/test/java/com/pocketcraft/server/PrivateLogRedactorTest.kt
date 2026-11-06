package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

class PrivateLogRedactorTest {
    @Test fun removesNxsAndLabeledCredentialsWithoutRemovingUsefulLogContext() {
        val raw="Failed to connect: token: 'pnx1.examplePrivate123456789' endpoint=https://nethernet.playit.gg password=topSecret!"
        val redacted=PrivateLogRedactor.redact(raw)
        assertFalse(redacted.contains("examplePrivate123456789"))
        assertFalse(redacted.contains("topSecret"))
        assertTrue(redacted.contains("nethernet.playit.gg"))
        assertTrue(redacted.contains("Failed to connect"))
    }
    @Test fun keepsOrdinaryServerLogReadable() {
        assertEquals("[Server thread/INFO]: Done (1.23s)!",
            PrivateLogRedactor.redact("[Server thread/INFO]: Done (1.23s)!"))
    }
    @Test fun removesBearerAuthorization() {
        assertFalse(PrivateLogRedactor.redact("Authorization: Bearer abcdefghijk1234567890")
            .contains("abcdefghijk1234567890"))
    }
}
