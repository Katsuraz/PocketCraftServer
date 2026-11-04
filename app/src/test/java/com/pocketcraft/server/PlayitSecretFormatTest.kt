package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayitSecretFormatTest {
    private val key = "0123456789abcdef".repeat(4)

    @Test fun acceptsExistingAgentHexKey() {
        assertEquals(key,PlayitSecretFormat.normalize(key))
        assertEquals(key,PlayitSecretFormat.normalize("  $key  "))
    }

    @Test fun acceptsExistingAgentTomlFormat() {
        assertEquals(key,PlayitSecretFormat.normalize("secret_key = \"$key\""))
        assertEquals("secret_key = \"$key\"\n",PlayitSecretFormat.toToml(key))
    }

    @Test fun rejectsInvalidOrInjectedValues() {
        val cases = listOf("","abc","secret_key = \"oops\"",
            "secret_key = \"$key\"\nadmin = true",
            "some_other_key = \"$key\"",
            "${key}g","http://example.com/$key")
        for (entry in cases) {
            var rejected = false
            try { PlayitSecretFormat.normalize(entry) } catch (_:IllegalArgumentException) {
                rejected = true
            }
            assertEquals("Expected rejection for malformed credential",true,rejected)
        }
    }
}
