// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.utils

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.File
import android.os.ParcelFileDescriptor
import java.net.URLDecoder
import java.nio.file.Paths
import java.util.StringTokenizer
import java.util.concurrent.ConcurrentHashMap
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.model.CheapDocument
import org.citra.citra_emu.utils.BuildUtil

/**
 * A cached document tree for Citra user directory.
 * For every filepath which is not startsWith "content://" will need to use this class to traverse.
 * For example:
 * C++ Citra log file directory will be /log/azahar_log.txt.
 * After DocumentsTree.resolvePath() it will become content URI.
 */
class DocumentsTree {
    private var root: DocumentsNode? = null
    private var filesystemRoot: File? = null
    private val context get() = CitraApplication.appContext

            fun setRoot(rootUri: Uri?) {
        root = null
        root = DocumentsNode()
        root!!.uri = rootUri
        root!!.isDirectory = true
    }

    fun setFilesystemRoot(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
        }

        require(directory.isDirectory) {
            "Azahar data directory is not a directory"
        }

        filesystemRoot = directory.canonicalFile

        root = DocumentsNode(filesystemRoot!!).apply {
            isDirectory = true
            loaded = false
        }
    }

            @Synchronized
    fun createFile(filepath: String, name: String): Boolean {
        val node = resolvePath(filepath) ?: return false
        if (!node.isDirectory) return false
        if (!node.loaded) structTree(node)

        try {
            val filename = URLDecoder.decode(name, FileUtil.DECODE_METHOD)
            if (node.findChild(filename) != null) return true

            val parentFile = node.file

            if (parentFile != null) {
                val file = File(parentFile, filename)

                if (file.exists() && !file.isFile) return false
                if (!file.exists() && !file.createNewFile()) return false

                val document = DocumentsNode(file)
                document.parent = node
                node.addChild(document)
                return true
            }

            val createdFile =
                FileUtil.createFile(node.uri.toString(), name) ?: return false

            val document = DocumentsNode(createdFile, false)
            document.parent = node
            node.addChild(document)
            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot create file, error: " + e.message)
        }
    }

    @Synchronized
    fun createDir(filepath: String, name: String): Boolean {
        val node = resolvePath(filepath) ?: return false
        if (!node.isDirectory) return false
        if (!node.loaded) structTree(node)

        try {
            val filename = URLDecoder.decode(name, FileUtil.DECODE_METHOD)
            if (node.findChild(filename) != null) return true

            val parentFile = node.file

            if (parentFile != null) {
                val directory = File(parentFile, filename)

                if (directory.exists() && !directory.isDirectory) return false
                if (!directory.exists() && !directory.mkdirs()) return false

                val document = DocumentsNode(directory)
                document.parent = node
                node.addChild(document)
                return true
            }

            val createdDirectory =
                FileUtil.createDir(node.uri.toString(), name) ?: return false

            val document = DocumentsNode(createdDirectory, true)
            document.parent = node
            node.addChild(document)
            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot create directory, error: " + e.message)
        }
    }

        @Synchronized
    fun openContentUri(filepath: String, openMode: String): Int {
        val node = resolvePath(filepath) ?: return -1
        val file = node.file

        if (file != null) {
            return try {
                val mode = when (openMode) {
                    "r" -> ParcelFileDescriptor.MODE_READ_ONLY
                    "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE
                    "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_APPEND
                    "rwa" -> ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_APPEND
                    else -> throw IllegalArgumentException(
                        "Unsupported file open mode: $openMode"
                    )
                }

                ParcelFileDescriptor.open(file, mode).detachFd()
            } catch (e: Exception) {
                android.util.Log.e(
                    "DocumentsTree",
                    "Cannot open physical file: ${e.message}"
                )
                -1
            }
        }

        return FileUtil.openContentUri(node.uri.toString(), openMode)
    }
    
    @Synchronized
    fun getFilename(filepath: String): String {
        val components = filepath.split(DELIMITER).filter { it.isNotEmpty() }
        val filename = components.last()
        return filename
    }

    @Synchronized
    fun getFilesName(filepath: String): Array<String?> {
        val node = resolvePath(filepath)
        if (node == null || !node.isDirectory) {
            return arrayOfNulls(0)
        }
        // If this directory has not been iterated, struct it.
        if (!node.loaded) structTree(node)
        return node.getChildNames()
    }

        @Synchronized
    fun getFileSize(filepath: String): Long {
        val node = resolvePath(filepath)

        return if (node == null || node.isDirectory) {
            0
        } else {
            node.file?.length()
                ?: FileUtil.getFileSize(node.uri.toString())
        }
    }

        @Synchronized
    fun getUri(filepath: String): Uri {
        val node = resolvePath(filepath) ?: return Uri.EMPTY
        return node.uri ?: node.file?.toUri() ?: Uri.EMPTY
    }

            @Synchronized
    fun folderUriHelper(path: String, createIfNotExists: Boolean = false): Uri? {
        var current = root ?: return null
        val components = path.split(DELIMITER).filter { it.isNotEmpty() }

        for (component in components) {
            if (!current.loaded) {
                structTree(current)
            }

            var child = current.findChild(component)

            if (child == null && createIfNotExists) {
                try {
                    val parentFile = current.file

                    if (parentFile != null) {
                        val directory = File(parentFile, component)

                        if (!directory.exists() && !directory.mkdirs()) {
                            return null
                        }

                        child = DocumentsNode(directory)
                    } else {
                        val parentUri = current.uri ?: return null
                        val createdDir =
                            FileUtil.createDir(parentUri.toString(), component)
                                ?: return null

                        child = DocumentsNode(createdDir, true)
                    }

                    child.parent = current
                    current.addChild(child)
                } catch (e: Exception) {
                    error("[DocumentsTree]: Cannot create directory, error: " + e.message)
                    return null
                }
            } else if (child == null) {
                return null
            }

            current = child
        }

        return current.uri ?: current.file?.toUri()
    }
            

    @Synchronized
    fun isDirectory(filepath: String): Boolean {
        val node = resolvePath(filepath) ?: return false
        return node.isDirectory
    }

    @Synchronized
    fun exists(filepath: String): Boolean = resolvePath(filepath) != null


    
    @Synchronized
    fun copyFile(
        sourcePath: String,
        destinationParentPath: String,
        destinationFilename: String
    ): Boolean {
        val sourceNode = resolvePath(sourcePath) ?: return false
        val destinationNode = resolvePath(destinationParentPath) ?: return false

        if (sourceNode.isDirectory || !destinationNode.isDirectory) return false

        val filename = URLDecoder.decode(destinationFilename, FileUtil.DECODE_METHOD)

        if (filename.isBlank() ||
            filename == "." ||
            filename == ".." ||
            filename.contains("/") ||
            filename.contains("\\")
        ) {
            return false
        }

        try {
            if (!destinationNode.loaded) {
                structTree(destinationNode)
            }

            if (destinationNode.findChild(filename) != null) return false

            var destinationFile: File? = null
            var destinationDocument: DocumentFile? = null

            try {
                if (destinationNode.file != null) {
                    val parentFile = destinationNode.file!!
                    val target = File(parentFile, filename)

                    if (target.canonicalFile.parentFile != parentFile.canonicalFile) {
                        return false
                    }

                    if (!target.createNewFile()) return false
                    destinationFile = target
                } else {
                    val parentUri = destinationNode.uri ?: return false
                    val parent = DocumentFile.fromTreeUri(context, parentUri)
                        ?: return false

                    if (parent.findFile(filename)?.exists() == true) return false

                    destinationDocument = parent.createFile(
                        FileUtil.APPLICATION_OCTET_STREAM,
                        filename
                    ) ?: return false
                }

                val sourceInput = sourceNode.file?.inputStream()
                    ?: run {
                        val sourceUri = sourceNode.uri
                            ?: throw IOException("Source URI unavailable")

                        context.contentResolver.openInputStream(sourceUri)
                            ?: throw IOException("Cannot open source file")
                    }

                sourceInput.use { input ->
                    val destinationOutput = destinationFile?.outputStream()
                        ?: run {
                            val destinationUri = destinationDocument?.uri
                                ?: throw IOException("Destination URI unavailable")

                            context.contentResolver.openOutputStream(
                                destinationUri,
                                "wt"
                            ) ?: throw IOException("Cannot open destination file")
                        }

                    destinationOutput.use { output ->
                        input.copyTo(output)
                    }
                }

                val document = destinationFile?.let { DocumentsNode(it) }
                    ?: DocumentsNode(destinationDocument!!, false)

                document.parent = destinationNode
                destinationNode.addChild(document)

                return true
            } catch (e: Exception) {
                destinationFile?.delete()
                destinationDocument?.delete()
                throw e
            }
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot copy file, error: " + e.message)
            return false
        }
    }



    
    @Synchronized
    fun moveFile(
        filename: String,
        sourceDirPath: String,
        destDirPath: String
    ): Boolean {
        val sourcePath = "$sourceDirPath/$filename"
        val destinationPath = "$destDirPath/$filename"

        val sourceNode = resolvePath(sourcePath) ?: return false
        val destinationNode = resolvePath(destDirPath) ?: return false

        if (sourceNode.isDirectory || !destinationNode.isDirectory) {
            return false
        }

        if (!copyFile(sourcePath, destDirPath, filename)) {
            return false
        }

        if (deleteDocument(sourcePath)) {
            return true
        }

        if (!deleteDocument(destinationPath)) {
            error(
                "[DocumentsTree]: Cannot roll back copied file after failed move"
            )
        }

        return false
    }


    
    @Synchronized
    fun deleteDocument(filepath: String): Boolean {
        val node = resolvePath(filepath) ?: return false

        try {
            val deleted = if (node.file != null) {
                val file = node.file!!
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            } else {
                val uri = node.uri ?: return false
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }

            if (!deleted) return false

            node.parent?.removeChild(node)
            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot delete file, error: " + e.message)
            return false
        }
    }

 
    @Synchronized
    fun renameFile(filepath: String, destinationFilename: String): Boolean {
        val node = resolvePath(filepath) ?: return false
        val parent = node.parent ?: return false

        try {
            val filename = URLDecoder.decode(
                destinationFilename,
                FileUtil.DECODE_METHOD
            )

            if (filename.isBlank() ||
                filename == "." ||
                filename == ".." ||
                filename.contains("/") ||
                filename.contains("\\")
            ) {
                return false
            }

            if (filename == node.name) return true

            if (!parent.loaded) {
                structTree(parent)
            }

            val existingNode = parent.findChild(filename)
            if (existingNode != null && existingNode !== node) {
                return false
            }

            val sourceFile = node.file

            if (sourceFile != null) {
                val destinationFile = File(sourceFile.parentFile, filename)

                if (destinationFile.canonicalFile.parentFile !=
                    sourceFile.parentFile?.canonicalFile
                ) {
                    return false
                }

                if (destinationFile.exists()) return false
                if (!sourceFile.renameTo(destinationFile)) return false

                parent.removeChild(node)
                node.file = destinationFile
                node.name = filename
                parent.addChild(node)

                return true
            }

            val sourceUri = node.uri ?: return false

            val newUri = DocumentsContract.renameDocument(
                context.contentResolver,
                sourceUri,
                filename
            ) ?: return false

            node.rename(filename, newUri)

            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot rename file, error: " + e.message)
            return false
        }
    }
    
    
    @Synchronized
    fun updateDocumentLocation(sourcePath: String, destinationPath: String): Boolean {
        val sourceNode = resolvePath(sourcePath) ?: return false
        val newName = Paths.get(destinationPath).fileName?.toString()
            ?: return false
        val parentPath = Paths.get(destinationPath).parent?.toString()
            ?: return false
        val newParent = resolvePath(parentPath) ?: return false

        if (!newParent.isDirectory) return false

        try {
            if (!newParent.loaded) {
                structTree(newParent)
            }

            val existingNode = newParent.findChild(newName)
            if (existingNode != null && existingNode !== sourceNode) {
                return false
            }

            val oldParent = sourceNode.parent ?: return false
            val sourceFile = sourceNode.file

            if (sourceFile != null) {
                val destinationParentFile = newParent.file ?: return false
                val destinationFile = File(destinationParentFile, newName)

                if (destinationFile.canonicalFile.parentFile !=
                    destinationParentFile.canonicalFile
                ) {
                    return false
                }

                if (destinationFile.exists()) return false

                oldParent.removeChild(sourceNode)
                sourceNode.file = destinationFile
                sourceNode.name = newName
                sourceNode.parent = newParent
                newParent.addChild(sourceNode)

                return true
            }

            val parentUri = newParent.uri ?: return false
            val newUri = DocumentsContract.buildDocumentUriUsingTree(
                parentUri,
                DocumentsContract.getDocumentId(parentUri) + "/" + newName
            )

            
        } catch (e: Exception) {
            error(
                "[DocumentsTree]: Cannot update document location, error: " + e.message
            )
            return false
        }
    }

        sourceNode.parent!!.removeChild(sourceNode)

        sourceNode.name = newName
        sourceNode.parent = newParent
        sourceNode.uri = newUri

        newParent.addChild(sourceNode)

        return true
    }

    @Synchronized
    private fun resolvePath(filepath: String): DocumentsNode? {
        if (!BuildUtil.isGooglePlayBuild) {
            var isLegalPath = false
            kotlinDirectoryAccessWhitelist.forEach {
                if (filepath.startsWith(it)) {
                    isLegalPath = true
                }
            }
            if (!isLegalPath) {
                throw IOException("Attempted to resolve forbidden path: " + filepath)
            }
        }
        root ?: return null
        val tokens = StringTokenizer(filepath, DELIMITER, false)
        var iterator = root
        while (tokens.hasMoreTokens()) {
            val token = tokens.nextToken()
            if (token.isEmpty()) continue
            iterator = find(iterator!!, token)
            if (iterator == null) return null
        }
        return iterator
    }

    @Synchronized
    private fun find(parent: DocumentsNode, filename: String): DocumentsNode? {
        if (parent.isDirectory && !parent.loaded) {
            structTree(parent)
        }
        return parent.findChild(filename)
    }

    /**
     * Construct current level directory tree
     *
     * @param parent parent node of this level
     */
    @Synchronized
private fun structTree(parent: DocumentsNode) {
    val parentFile = parent.file

    if (parentFile != null) {
        val files = parentFile.listFiles()

        if (files != null) {
            for (file in files) {
                val node = DocumentsNode(file)
                node.parent = parent
                parent.addChild(node)
            }
        }

        parent.loaded = true
        return
    }

    val documents = FileUtil.listFiles(parent.uri!!)
    for (document in documents) {
        val node = DocumentsNode(document)
        node.parent = parent
        parent.addChild(node)
    }

    parent.loaded = true
}

    private class DocumentsNode {
        @get:Synchronized
        @set:Synchronized
        var parent: DocumentsNode? = null
        val children: MutableMap<String?, DocumentsNode?> = ConcurrentHashMap()
        lateinit var name: String

        @get:Synchronized
@set:Synchronized
var uri: Uri? = null

var file: File? = null

@get:Synchronized
@set:Synchronized
var loaded = false

var isDirectory = false

constructor()

constructor(file: File) {
    this.file = file
    name = file.name
    isDirectory = file.isDirectory
    loaded = !file.isDirectory
}

constructor(document: CheapDocument) {
    name = document.filename
    uri = document.uri
    isDirectory = document.isDirectory
    loaded = !isDirectory
}
        constructor(document: DocumentFile, isCreateDir: Boolean) {
            name = document.name!!
            uri = document.uri
            isDirectory = isCreateDir
            loaded = true
        }

        @Synchronized
        fun rename(name: String, uri: Uri?) {
            parent ?: return
            parent!!.removeChild(this)
            this.name = name
            this.uri = uri
            parent!!.addChild(this)
        }

        fun addChild(node: DocumentsNode) {
            children[node.name.lowercase()] = node
        }

        fun removeChild(node: DocumentsNode) = children.remove(node.name.lowercase())

        fun findChild(filename: String) = children[filename.lowercase()]

        @Synchronized
        fun getChildNames(): Array<String?> = children.mapNotNull { it.value!!.name }.toTypedArray()
    }

    companion object {
        const val DELIMITER = "/"
        val kotlinDirectoryAccessWhitelist = arrayOf(
            "/config/",
            "/log/",
            "/gpu_drivers/"
        )
    }
}
