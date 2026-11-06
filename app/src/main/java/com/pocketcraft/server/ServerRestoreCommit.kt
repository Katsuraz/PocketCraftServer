package com.pocketcraft.server

import java.io.File
import java.io.IOException

/**
 * Applies a staged restore to an empty server with rollback on a failed move.
 * Never overwrites an existing server file, never touches server credentials.
 */
internal object ServerRestoreCommit {
    fun apply(staged: File, root: File, move: (File, File) -> Boolean = { from,to ->
        from.renameTo(to)
    }): Int {
        require(staged.isDirectory && root.isDirectory) { "Invalid restore directories" }
        val stageRoot=staged.canonicalFile
        val targetRoot=root.canonicalFile
        if(stageRoot==targetRoot || stageRoot.path.startsWith(targetRoot.path+File.separator) ||
            targetRoot.path.startsWith(stageRoot.path+File.separator))
            throw IOException("Backup staging and server folder overlap")
        val sourceFiles=stageRoot.walkTopDown().filter { it.isFile }.toList()
        if(sourceFiles.isEmpty() || sourceFiles.size>50_000)
            throw IOException("Backup contains an invalid number of files")
        val mappings=sourceFiles.map { src ->
            val name=src.relativeTo(stageRoot).invariantSeparatorsPath
            if(java.nio.file.Files.isSymbolicLink(src.toPath()))
                throw IOException("Backup contains a file link")
            val dest=File(targetRoot,name).canonicalFile
            if(!dest.path.startsWith(targetRoot.path+File.separator))
                throw IOException("Invalid restore file path")
            if(dest.exists() || (dest.parentFile?.let { it.exists() && !it.isDirectory } == true))
                throw IOException("Restore would overwrite existing server files")
            src to dest
        }
        val created=ArrayList<File>()
        val madeFolders=linkedSetOf<File>()
        try {
            for((src,dest) in mappings) {
                val pending=ArrayList<File>()
                var directory=dest.parentFile
                while(directory!=null && directory!=targetRoot && !directory.exists()) {
                    pending.add(directory)
                    directory=directory.parentFile
                }
                for(folder in pending.asReversed()) {
                    if(folder.mkdir()) madeFolders.add(folder)
                    else if(!folder.isDirectory) throw IOException("Cannot create restore folder")
                }
                if(dest.exists() || !move(src,dest))
                    throw IOException("Could not restore file; previous transfers will be reverted")
                created.add(dest)
            }
            return created.size
        } catch(e:Exception) {
            created.asReversed().forEach { it.delete() }
            madeFolders.toList().asReversed().forEach { folder ->
                if(folder.listFiles().isNullOrEmpty()) folder.delete()
            }
            throw IOException("Restore failed and transferred files were rolled back",e)
        }
    }
}
