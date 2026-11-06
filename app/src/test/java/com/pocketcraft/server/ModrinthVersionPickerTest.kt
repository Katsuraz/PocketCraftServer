package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ModrinthVersionPickerTest {
    private fun version(number:String,type:String,published:String) =
        ModrinthCatalog.Version("id-"+number,"fabric-api","Fabric API",
            number,type,published,1000,0,0,0,"fabric-api.jar",
            "https://cdn.modrinth.com/data/example.jar",null,listOf("26.3"),"server_only")

    @Test fun channelAndSearchFilterExactlyTheChosenReleases() {
        val values=listOf(
            version("0.165.2+26.3","release","2026-11-03"),
            version("0.164.0+26.3","beta","2026-10-23"),
            version("0.163.0+26.3","alpha","2026-10-19"))
        assertEquals(3,ModrinthVersionPicker.filter(values,"all","").size)
        assertEquals(listOf("0.164.0+26.3"),
            ModrinthVersionPicker.filter(values,"beta","").map{it.number})
        assertEquals(listOf("0.165.2+26.3"),
            ModrinthVersionPicker.filter(values,"release","0.165").map{it.number})
        assertEquals(listOf("0.164.0+26.3"),
            ModrinthVersionPicker.filter(values,"all","2026-10-23").map{it.number})
        assertEquals(listOf("0.163.0+26.3","0.164.0+26.3","0.165.2+26.3"),
            ModrinthVersionPicker.filter(values,"all","",true).map{it.number})
        assertEquals(0,ModrinthVersionPicker.filter(values,"alpha","does-not-exist").size)
    }
    @Test fun searchCanFilterByMinecraftVersionOrLoader() {
        val old=version("0.90","release","2023-10-12")
            .copy(gameVersions=listOf("1.20.1"),loaders=listOf("fabric"))
        val other=version("1.0","beta","2026-09-15")
            .copy(gameVersions=listOf("26.3"),loaders=listOf("neoforge"))
        val values=listOf(old,other)
        assertEquals(listOf(old),
            ModrinthVersionPicker.filter(values,"all","1.20.1"))
        assertEquals(listOf(other),
            ModrinthVersionPicker.filter(values,"all","neoforge"))
        assertEquals(2,ModrinthVersionPicker.filter(values,"all","").size)
    }

}
