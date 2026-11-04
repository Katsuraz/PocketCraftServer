package com.pocketcraft.server

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var console: TextView
    private val root by lazy { File(filesDir, "minecraft").apply { mkdirs() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 40, 30, 20) }
        val title = TextView(this).apply { text = "PocketCraft Server"; textSize = 26f }
        status = TextView(this).apply { text = "Status: not running" }
        console = TextView(this).apply { text = "Import a Fabric server JAR and compatible mods. A Java runtime is not bundled in this prototype." }
        layout.addView(title); layout.addView(status)
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Import Fabric server JAR") { pick("application/java-archive", 1) }
        button("Import Fabric mod JAR") { pick("application/java-archive", 2) }
        button("Start server") {
            startService(Intent(this, ServerService::class.java).setAction("START"))
            status.text = "Start requested (runtime required)"
        }
        button("Stop server") {
            startService(Intent(this, ServerService::class.java).setAction("STOP"))
            status.text = "Stop requested"
        }
        button("Refresh console") { console.text = File(root, "console.log").takeIf { it.exists() }?.readText()?.takeLast(8000) ?: "No server output yet" }
        button("Back up world") {
            val world = File(root, "world")
            if (!world.isDirectory) { Toast.makeText(this, "No world found", Toast.LENGTH_SHORT).show(); return@button }
            val out = File(filesDir, "world-backup.zip")
            ZipOutputStream(out.outputStream()).use { zip ->
                world.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            Toast.makeText(this, "Backup saved to app files: ${out.name}", Toast.LENGTH_LONG).show()
        }
        layout.addView(ScrollView(this).apply { addView(console) })
        setContentView(layout)
    }
    private fun pick(type: String, request: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); setType(type)
        }, request)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val target = if (requestCode == 1) File(root, "fabric-server-launch.jar")
            else File(root, "mods").apply { mkdirs() }.resolve("mod-${System.currentTimeMillis()}.jar")
        contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        Toast.makeText(this, "Imported ${target.name}", Toast.LENGTH_SHORT).show()
    }
}
