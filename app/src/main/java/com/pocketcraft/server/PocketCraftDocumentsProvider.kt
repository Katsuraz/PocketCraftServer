package com.pocketcraft.server

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Makes the SAME live server files accessible through Android's Storage Access Framework.
 * No copying/moving game data, no broad storage permission and no root.
 *
 * Only Android's system picker can bind to this provider (MANAGE_DOCUMENTS permission).
 * Changes are disabled while a server process is running.
 */
class PocketCraftDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.pocketcraft.server.documents"
        const val ROOT_ID = "pocketcraft-server"
        private const val ROOT_DOC = "root"
        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )
        private val DOC_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
    }

    override fun onCreate(): Boolean = true

    private fun root(): File = WorldStore.root(requireNotNull(context)).canonicalFile

    private fun file(id: String): File {
        val base = root()
        if (id == ROOT_DOC) return base
        if (!id.startsWith("$ROOT_DOC/")) throw FileNotFoundException("Invalid document")
        val relative = id.substring(ROOT_DOC.length + 1)
        if (relative.isEmpty() || relative.split('/').any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') })
            throw FileNotFoundException("Invalid path")
        val candidate = File(base, relative).canonicalFile
        if (!candidate.path.startsWith(base.path + File.separator))
            throw FileNotFoundException("Outside server directory")
        return candidate
    }

    private fun id(item: File): String {
        val base = root()
        val canonical = item.canonicalFile
        if (canonical == base) return ROOT_DOC
        if (!canonical.path.startsWith(base.path + File.separator))
            throw FileNotFoundException("Outside server directory")
        return "$ROOT_DOC/" + canonical.relativeTo(base).invariantSeparatorsPath
    }

    private fun mime(item: File): String {
        if (item.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        return when(item.extension.lowercase()) {
            "yml", "yaml" -> "text/yaml"
            "txt", "properties", "log", "cfg", "toml", "mcfunction" -> "text/plain"
            "json", "mcmeta" -> "application/json"
            "jar" -> "application/java-archive"
            "zip", "mrpack" -> "application/zip"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.extension.lowercase())
                ?: "application/octet-stream"
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: ROOT_COLUMNS
        return MatrixCursor(cols).apply {
            val row = newRow()
            cols.forEach { column ->
                row.add(column, when(column) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_DOC
                    DocumentsContract.Root.COLUMN_TITLE -> "PocketCraft Server"
                    DocumentsContract.Root.COLUMN_SUMMARY -> "Live Minecraft files"
                    DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_LOCAL_ONLY or
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                    DocumentsContract.Root.COLUMN_ICON -> R.drawable.pocket_logo
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> root().usableSpace
                    DocumentsContract.Root.COLUMN_MIME_TYPES -> "*/*"
                    else -> null
                })
            }
        }
    }

    private fun row(cursor: MatrixCursor, item: File) {
        val writeable = !ServerService.running
        val flags = if (item.isDirectory) {
            (if (writeable) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0)
        } else if (writeable) DocumentsContract.Document.FLAG_SUPPORTS_WRITE else 0
        val operations = if (writeable && item != root())
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        else 0
        val values = cursor.newRow()
        cursor.columnNames.forEach { column ->
            values.add(column, when(column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id(item)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if(item == root()) "PocketCraft Server" else item.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> mime(item)
                DocumentsContract.Document.COLUMN_SIZE -> if(item.isFile) item.length() else null
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> item.lastModified()
                DocumentsContract.Document.COLUMN_FLAGS -> flags or operations
                else -> null
            })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val target = file(documentId)
        if (!target.exists()) throw FileNotFoundException("File missing")
        return MatrixCursor(projection ?: DOC_COLUMNS).apply { row(this, target) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = file(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Folder missing")
        return MatrixCursor(projection ?: DOC_COLUMNS).apply {
            parent.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.forEach { child -> runCatching { row(this, file(id(child))) } }
            setNotificationUri(requireNotNull(context).contentResolver,
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val target = file(documentId)
        if (!target.isFile) throw FileNotFoundException("File missing")
        val changing = mode != "r"
        if (changing && ServerService.running)
            throw FileNotFoundException("Stop Minecraft before editing files")
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }

    private fun stopped() {
        if (ServerService.running) throw IllegalStateException("Stop Minecraft before editing server files")
    }

    private fun checkedName(name: String) {
        if(name.isBlank() || name.length > 180 || name in listOf(".", "..") ||
            name.any { it == '/' || it == '\\' || it == '\u0000' })
            throw IllegalArgumentException("Invalid filename")
    }

    private fun changed(documentId: String) {
        context?.contentResolver?.notifyChange(DocumentsContract.buildDocumentUri(AUTHORITY, documentId), null)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        stopped()
        checkedName(displayName)
        val parent = file(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Folder missing")
        val child = file(parentDocumentId + "/" + displayName)
        if (child.exists()) throw IOException("Name already exists")
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) child.mkdir()
            else child.createNewFile()
        if (!created) throw IOException("Cannot create document")
        changed(parentDocumentId)
        return id(child)
    }

    override fun deleteDocument(documentId: String) {
        stopped()
        val target = file(documentId)
        if (target == root()) throw FileNotFoundException("Cannot delete server root")
        // Make folder deletion conservative; never delete an entire world with one tap.
        if (target.isDirectory && !target.listFiles().isNullOrEmpty())
            throw IOException("Folder is not empty; delete its contents explicitly")
        if (!target.delete()) throw IOException("Could not delete file")
        changed(id(target.parentFile ?: root()))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        stopped()
        checkedName(displayName)
        val target = file(documentId)
        if (target == root() || !target.exists()) throw FileNotFoundException("Cannot rename this document")
        val parent = target.parentFile ?: throw FileNotFoundException("No parent folder")
        val next = file(id(parent) + "/" + displayName)
        if (next.exists() || !target.renameTo(next)) throw IOException("Cannot rename file")
        changed(id(parent))
        return id(next)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return runCatching {
            val parent = file(parentDocumentId).canonicalPath + File.separator
            file(documentId).canonicalPath.startsWith(parent)
        }.getOrDefault(false)
    }
}
