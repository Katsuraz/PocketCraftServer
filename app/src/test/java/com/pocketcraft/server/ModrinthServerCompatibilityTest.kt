package com.pocketcraft.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class ModrinthServerCompatibilityTest {
    @Test fun browseAllProjectsWithoutHiddenGameLoaderOrEnvironmentRestrictions() {
        val uri=ModrinthCatalog.searchUrl("mod","","26.3","all","relevance",0)
        val decoded=URLDecoder.decode(uri,"UTF-8")
        val facets=decoded.substringAfter("facets=").substringBefore("&index=")
        val groups=JSONArray(facets)
        assertEquals(1,groups.length())
        assertEquals("project_type:mod",groups.getJSONArray(0).getString(0))
        assertFalse(decoded.contains("versions:26.3"))
        assertFalse(decoded.contains("categories:fabric"))
        assertFalse(decoded.contains("environment:"))
        assertTrue(ModrinthCatalog.onlyClientSide("unsupported",listOf("client_only")))
        assertFalse(ModrinthCatalog.onlyClientSide("optional",
            listOf("client_only_server_optional")))
        // Client-only projects are now visible with a warning, not excluded.
    }

    private fun version(
        environment:String="server_only",filename:String="server.jar",
        url:String="https://cdn.modrinth.com/data/test/server.jar",
        game:String="26.3",loader:String="fabric",sha:String="a".repeat(128)
    ):JSONObject = JSONObject().apply {
        put("id","V1234")
        put("project_id","P1234")
        put("name","Example 1.0")
        put("version_number","1.0+26.3")
        put("version_type","release")
        put("date_published","2026-11-04T12:00:00Z")
        put("downloads",123)
        put("game_versions",JSONArray().put(game))
        put("loaders",JSONArray().put(loader))
        put("environment",environment)
        put("dependencies",JSONArray())
        put("files",JSONArray().put(JSONObject().apply {
            put("filename",filename)
            put("url",url)
            put("primary",true)
            put("hashes",JSONObject().put("sha512",sha))
        }))
    }

    @Test fun allPublishedVersionsCanBeSelectedAndWarningsIdentifyRisks() {
        val normal=ModrinthCatalog.parseVersion("P1234","mod","26.3",version())
        assertNotNull(normal)
        assertTrue(ModrinthCatalog.compatibilityWarnings(normal!!,"26.3").isEmpty())

        val older=ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(game="1.20.1"))
        assertNotNull(older)
        assertTrue(ModrinthCatalog.compatibilityWarnings(older!!,"26.3")
            .any{ it.contains("1.20.1") })

        val clientOnly=ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(environment="client_only"))
        assertNotNull(clientOnly)
        assertTrue(ModrinthCatalog.compatibilityWarnings(clientOnly!!,"26.3")
            .any{ it.contains("Client-only") })

        val otherLoader=ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(loader="neoforge"))
        assertNotNull(otherLoader)
        assertTrue(ModrinthCatalog.compatibilityWarnings(otherLoader!!,"26.3")
            .any{ it.contains("neoforge") })
    }

    @Test fun downloadsStillRejectUnsafeFilesAndMissingChecksums() {
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(filename="source.zip")))
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(url="https://untrusted.example/malware.jar")))
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(sha="broken")))
        assertNull(ModrinthCatalog.parseVersion("wrongProject","mod","26.3",version()))
        val pack=ModrinthCatalog.parseVersion("P1234","modpack","26.3",
            version(filename="pack.mrpack",
                url="https://cdn.modrinth.com/data/test/pack.mrpack"))
        assertNotNull(pack)
        assertEquals("pack.mrpack",pack!!.filename)
    }

    @Test fun advisoryClientEligibilityRemainsAccurate() {
        assertTrue(ModrinthCatalog.isServerEligible("unknown"))
        assertTrue(ModrinthCatalog.isServerEligible("client_and_server"))
        assertTrue(ModrinthCatalog.isServerEligible("client_only_server_optional"))
        assertFalse(ModrinthCatalog.isServerEligible("client_only"))
        assertFalse(ModrinthCatalog.isServerEligible("singleplayer_only"))
    }
}
