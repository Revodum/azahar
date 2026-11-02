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

        val filename = URLDecoder.decode(destinationFilename, "UTF-8")

        if (destinationNode.findChild(filename) != null) return false

        try {
            val sourceFile = sourceNode.file

            if (sourceFile != null) {
                val parentFile = destinationNode.file ?: return false
                val destinationFile = File(parentFile, filename)

                if (destinationFile.exists()) return false

                try {
                    sourceFile.inputStream().use { input ->
                        destinationFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val document = DocumentsNode(destinationFile)
                    document.parent = destinationNode
                    destinationNode.addChild(document)

                    return true
                } catch (e: Exception) {
                    destinationFile.delete()
                    throw e
                }
            }

            val sourceUri = sourceNode.uri ?: return false
            val destinationUri = destinationNode.uri ?: return false

            val destinationParent =
                DocumentFile.fromTreeUri(context, destinationUri) ?: return false

            val destination = destinationParent.createFile(
                "application/octet-stream",
                filename
            ) ?: return false

            try {
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: throw IOException("Cannot open source file")

                val output = context.contentResolver.openOutputStream(destination.uri, "wt")
                    ?: throw IOException("Cannot open destination file")

                input.use { source ->
                    output.use { target ->
                        source.copyTo(target)
                    }
                }

                val document = DocumentsNode(destination, false)
                document.parent = destinationNode
                destinationNode.addChild(document)

                return true
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot copy file, error: " + e.message)
        }
    }

    @Synchronized
    fun moveFile(filename: String, sourceDirPath: String, destDirPath: String): Boolean {
        val sourceFileNode = resolvePath(sourceDirPath + "/" + filename) ?: return false
        val sourceDirNode = resolvePath(sourceDirPath) ?: return false
        val destDirNode = resolvePath(destDirPath) ?: return false
        try {
            val newUri = DocumentsContract.moveDocument(
                context.contentResolver,
                sourceFileNode.uri!!,
                sourceDirNode.uri!!,
                destDirNode.uri!!
            )
            updateDocumentLocation("$sourceDirPath/$filename", "$destDirPath/$filename")
            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot move file, error: " + e.message)
        }
    }

    @Synchronized
    fun deleteDocument(filepath: String): Boolean {
        val node = resolvePath(filepath) ?: return false
        try {
            if (!DocumentsContract.deleteDocument(context.contentResolver, node.uri!!)) {
                return false
            }
            if (node.parent != null) {
                node.parent!!.removeChild(node)
            }
            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot rename file, error: " + e.message)
        }
    }

    @Synchronized
    fun updateDocumentLocation(sourcePath: String, destinationPath: String): Boolean {
        val sourceNode = resolvePath(sourcePath)
        val newName = Paths.get(destinationPath).fileName.toString()
        val parentPath = Paths.get(destinationPath).parent.toString()
        val newParent = resolvePath(parentPath)
        val newUri = (getUri(parentPath).toString() + "%2F$newName").toUri()
        // ^- Is there a better way?

        if (sourceNode == null || newParent == null) {
            return false
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
