package com.pocketcraft.server

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Read-only ZIP integrity check. Validates every entry and its CRC while
 * enforcing the same file/expanded-byte limits as full server restoration.
 */
internal object BackupArchiveVerifier {
    const val MAX_FILES = 50_000
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    data class Summary(val files: Int, val folders: Int, val expandedBytes: Long)

    fun verify(source: InputStream): Summary {
        val names = hashSetOf<String>()
        val files = hashSetOf<String>()
        var fileCount=0
        var folders=0
        var total=0L
        val bytes=ByteArray(32*1024)
        ZipInputStream(source.buffered()).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                val path=entry.name.replace('\\','/')
                if(path.startsWith("/") || path.contains('\u0000') ||
                    path.contains('\\') ||
                    path.split('/').any { it=="." || it==".." } ||
                    Regex("^[A-Za-z]:").containsMatchIn(path)) {
                    throw IOException("Unsafe path in backup ZIP")
                }
                val normalized=path.trimEnd('/')
                if(normalized.isBlank() || !names.add(normalized))
                    throw IOException("Duplicate or invalid backup entry")
                if(entry.isDirectory) {
                    folders++
                } else {
                    if(++fileCount>MAX_FILES)throw IOException("Too many backup files")
                    files.add(normalized)
                }
                while(true) {
                    val n=zip.read(bytes)
                    if(n<0)break
                    total+=n
                    if(total>MAX_BYTES)throw IOException("Backup exceeds 2 GB safety limit")
                }
                zip.closeEntry()
            }
        }
        if(fileCount==0)throw IOException("Backup ZIP contains no files")
        if(files.any { path ->
                val parents=path.split('/')
                (1 until parents.size).any {
                    parents.take(it).joinToString("/") in files
                }
            }) throw IOException("Backup has conflicting file paths")
        return Summary(fileCount,folders,total)
    }
}
