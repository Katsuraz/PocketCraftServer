package com.pocketcraft.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ServerFileOpsTest {
    @Test fun checksRootAndRejectsTraversal() {
        val root=Files.createTempDirectory("pc-file-root").toFile()
        val unrelated=Files.createTempDirectory("pc-unrelated").toFile()
        try {
            assertEquals(root.canonicalFile,ServerFileOps.checked(root,root))
            assertEquals(File(root,"config").canonicalFile,ServerFileOps.checked(root,File(root,"config")))
            var denied=false
            try {ServerFileOps.checked(root,unrelated)}catch(_:IOException){denied=true}
            assertTrue("Must refuse access outside Minecraft server folder",denied)
        }finally{root.deleteRecursively();unrelated.deleteRecursively()}
    }

    @Test fun editsMakeBackupsAndCanRestore() {
        val root=Files.createTempDirectory("pc-edit-root").toFile()
        try {
            val dir=File(root,"config/Geyser-Fabric").apply{mkdirs()}
            val config=File(dir,"config.yml").apply{writeText("bedrock:\n  port: 19132\n")}
            val backup=ServerFileOps.saveText(root,config,"bedrock:\n  port: 19133\n")
            assertEquals("bedrock:\n  port: 19133\n",config.readText())
            assertEquals("bedrock:\n  port: 19132\n",backup.readText())
            ServerFileOps.restore(root,config,backup)
            assertEquals("bedrock:\n  port: 19132\n",config.readText())
        }finally{root.deleteRecursively()}
    }

    @Test fun refusesEditingBinariesAndOversizedText() {
        val root=Files.createTempDirectory("pc-binary-root").toFile()
        try {
            val jar=File(root,"Geyser-Fabric.jar").apply{writeText("not a config")}
            val big=File(root,"oversize.yml").apply{writeText("a".repeat(270_000))}
            assertFalse(ServerFileOps.isEditable(jar))
            assertFalse(ServerFileOps.isEditable(big))
            var denied=false
            try{ServerFileOps.saveText(root,jar,"bad")}catch(_:IOException){denied=true}
            assertTrue(denied)
        }finally{root.deleteRecursively()}
    }
}
