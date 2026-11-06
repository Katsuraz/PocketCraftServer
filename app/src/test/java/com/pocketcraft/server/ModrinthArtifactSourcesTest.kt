package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModrinthArtifactSourcesTest {
    @Test fun mavenFallbackUsesOfficialModrinthForExactLithostitchedRelease() {
        val urls=ModrinthArtifactSources.mavenJarCandidates(
            "XaDC71GB","lithostitched","pZgI6b2t","2.0.6-fabric-26.3")
        assertEquals(2,urls.size)
        assertEquals("https://api.modrinth.com/maven/maven/modrinth/" +
            "XaDC71GB/pZgI6b2t/XaDC71GB-pZgI6b2t.jar",urls[0])
        assertEquals("https://api.modrinth.com/maven/maven/modrinth/" +
            "lithostitched/2.0.6-fabric-26.3/lithostitched-2.0.6-fabric-26.3.jar",urls[1])
        assertTrue(urls.all { it.startsWith("https://api.modrinth.com/maven/") })
    }

    @Test fun metadataTokensAreValidatedBeforeGeneratingUrls() {
        assertTrue(ModrinthArtifactSources.mavenJarCandidates(
            "../project","lithostitched","pZgI6b2t","2.0.6").isEmpty())
        assertTrue(ModrinthArtifactSources.mavenJarCandidates(
            "XaDC71GB","lithostitched","/etc/passwd","2.0.6").isEmpty())
        assertTrue(ModrinthArtifactSources.mavenJarCandidates(
            "XaDC71GB","host.attacker.com/../","pZgI6b2t","2.0.6").isEmpty())
        assertTrue(ModrinthArtifactSources.mavenJarCandidates(
            "XaDC71GB","","pZgI6b2t","2.0.6").isEmpty())
    }

    @Test fun plusInVersionNameIsEncodedAsPathDataNotAQuery() {
        val urls=ModrinthArtifactSources.mavenJarCandidates(
            "8oi3bsk5","terralith","aGE3hYAA","2.6.5+26.3")
        assertTrue(urls[1].contains("2.6.5%2B26.3"))
        assertFalse(urls[1].contains("?"))
        assertEquals(2,urls.distinct().size)
    }
}
