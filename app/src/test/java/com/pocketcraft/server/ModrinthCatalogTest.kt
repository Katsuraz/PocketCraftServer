package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

class ModrinthCatalogTest {
    @Test fun searchUsesOfficialAndFacetsWithServerVersion() {
        val url = ModrinthCatalog.searchUrl("mod","sodium", "1.21.11",
            "optimization","downloads",20)
        assertTrue(url.startsWith("https://api.modrinth.com/v2/search?"))
        assertTrue(url.contains("offset=20"))
        assertTrue(url.contains("limit=20"))
        assertTrue(url.contains("index=downloads"))
        assertTrue(java.net.URLDecoder.decode(url,"UTF-8").contains("versions:1.21.11"))
        assertTrue(java.net.URLDecoder.decode(url,"UTF-8").contains("categories:fabric"))
        assertTrue(java.net.URLDecoder.decode(url,"UTF-8").contains("categories:optimization"))
        assertTrue(java.net.URLDecoder.decode(url,"UTF-8").contains("project_type:mod"))
    }

    @Test fun rejectsUntrustedFiltersAndOutOfBoundsPages() {
        assertTrue(runCatching {
            ModrinthCatalog.searchUrl("plugin","","1.20.1","all","relevance",0)
        }.isFailure)
        assertTrue(runCatching {
            ModrinthCatalog.searchUrl("mod","","1.20.1","unknown","relevance",0)
        }.isFailure)
        assertTrue(runCatching {
            ModrinthCatalog.searchUrl("mod","","1.20.1","all","random",0)
        }.isFailure)
        assertTrue(runCatching {
            ModrinthCatalog.searchUrl("mod","","1.20.1","all","relevance",1)
        }.isFailure)
    }

    @Test fun clientOnlyCannotBeSelectedOnServer() {
        assertFalse(ModrinthCatalog.isServerEligible("client_only"))
        assertFalse(ModrinthCatalog.isServerEligible("singleplayer_only"))
        assertTrue(ModrinthCatalog.isServerEligible("client_or_server"))
        assertTrue(ModrinthCatalog.isServerEligible("server_only"))
        assertTrue(ModrinthCatalog.validIdentifier("P7dR8mSH"))
        assertFalse(ModrinthCatalog.validIdentifier("../bad"))
    }
}
