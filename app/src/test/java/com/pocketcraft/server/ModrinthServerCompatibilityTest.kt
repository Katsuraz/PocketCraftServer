package com.pocketcraft.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class ModrinthServerCompatibilityTest {
    @Test fun searchFacetsExcludeClientOnlyProjectsWithoutLosingServerMods() {
        val uri=ModrinthCatalog.searchUrl("mod","","26.3","all","relevance",0)
        val decoded=URLDecoder.decode(uri,"UTF-8")
        val facets=decoded.substringAfter("facets=").substringBefore("&index=")
        val groups=JSONArray(facets)
        val all=mutableListOf<List<String>>()
        for(i in 0 until groups.length()){
            val group=groups.getJSONArray(i)
            all.add((0 until group.length()).map { group.getString(it) })
        }
        assertTrue(all.contains(listOf("categories:fabric")))
        assertTrue(all.contains(listOf("versions:26.3")))
        assertTrue(all.contains(listOf("project_type:mod")))
        val serverOptions=all.first { it.any { value -> value.startsWith("environment:") } }
        assertTrue(serverOptions.contains("environment:client_and_server"))
        assertTrue(serverOptions.contains("environment:server_only"))
        assertTrue(serverOptions.contains("environment:client_or_server"))
        assertFalse(serverOptions.contains("environment:client_only"))
        assertFalse(serverOptions.contains("environment:singleplayer_only"))
        assertFalse("Client-only projects must be rejected",
            ModrinthCatalog.onlyClientSide("unsupported",listOf("client_only")) == false)
        assertTrue(ModrinthCatalog.onlyClientSide("unknown",listOf("client_only")))
        assertFalse(ModrinthCatalog.onlyClientSide("optional",listOf("client_only_server_optional")))
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

    @Test fun versionParserAcceptsFabricServerReleaseAndRejectsShaders() {
        val valid=ModrinthCatalog.parseVersion("P1234","mod","26.3",version())
        assertNotNull(valid)
        assertEquals("1.0+26.3",valid!!.number)
        assertEquals("server_only",valid.environment)
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(environment="client_only")))
        assertNull(ModrinthCatalog.parseVersion("P1234","modpack","26.3",
            version(environment="client_only",filename="pack.mrpack")))
    }

    @Test fun versionParserNeverOffersWrongFilesOrUnverifiedArtifacts() {
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(filename="source.zip")))
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(url="https://untrusted.example/malware.jar")))
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(sha="broken")))
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(game="26.2")))
        assertNull(ModrinthCatalog.parseVersion("P1234","mod","26.3",
            version(loader="neoforge")))
        assertNull(ModrinthCatalog.parseVersion("wrongProject","mod","26.3",version()))
        val pack=ModrinthCatalog.parseVersion("P1234","modpack","26.3",
            version(filename="pack.mrpack",
                url="https://cdn.modrinth.com/data/test/pack.mrpack"))
        assertNotNull(pack)
        assertEquals("pack.mrpack",pack!!.filename)
    }

    @Test fun unknownEnvironmentIsNotMistakenForClientOnly() {
        assertTrue(ModrinthCatalog.isServerEligible("unknown"))
        assertTrue(ModrinthCatalog.isServerEligible("client_and_server"))
        assertTrue(ModrinthCatalog.isServerEligible("client_only_server_optional"))
        assertFalse(ModrinthCatalog.isServerEligible("client_only"))
        assertFalse(ModrinthCatalog.isServerEligible("singleplayer_only"))
    }
}
