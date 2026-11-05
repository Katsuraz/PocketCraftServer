package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class MindustryHostFilesTest {
    @get:Rule val folder=TemporaryFolder()

    private fun jar(name:String,main:String,launcher:Boolean):File {
        val file=folder.newFile(name)
        val manifest=Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION]="1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS]=main
        }
        JarOutputStream(file.outputStream(),manifest).use {out->
            if(launcher){
                out.putNextEntry(JarEntry("mindustry/server/ServerLauncher.class"))
                out.write(byteArrayOf(1,2,3))
                out.closeEntry()
            }
        }
        return file
    }

    @Test fun acceptsDeclaredMindustryDedicatedEntrypoint() {
        val candidate=jar("server.jar","mindustry.server.ServerLauncher",true)
        MindustryHostFiles.validateJar(candidate)
        assertEquals(6567,MindustryHostFiles.DEFAULT_PORT)
    }

    @Test fun rejectsUnrelatedOrIncompleteGameJars() {
        assertThrows(IOException::class.java) {
            MindustryHostFiles.validateJar(jar("other.jar","some.game.Main",true))
        }
        assertThrows(IOException::class.java) {
            MindustryHostFiles.validateJar(jar("missing.jar","mindustry.server.ServerLauncher",false))
        }
    }

    @Test fun rejectsEmptyAndInvalidArchives() {
        val empty=folder.newFile("empty.jar")
        assertThrows(IOException::class.java) {MindustryHostFiles.validateJar(empty)}
        val bad=folder.newFile("bad.jar").apply{writeText("not a jar")}
        assertThrows(IOException::class.java) {MindustryHostFiles.validateJar(bad)}
    }
}
