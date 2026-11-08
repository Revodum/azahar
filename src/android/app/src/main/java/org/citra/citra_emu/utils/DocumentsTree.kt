// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.utils

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
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
    private val context get() = CitraApplication.appContext

    fun setRoot(rootUri: Uri?) {
        root = null
        root = DocumentsNode()
        val normalizedRootUri = if (
            !BuildUtil.isGooglePlayBuild &&
            rootUri != null &&
            rootUri.scheme == null &&
            rootUri.path?.startsWith("/") == true
        ) {
            Uri.fromFile(File(rootUri.path!!))
        } else {
            rootUri
        }
        root!!.uri = normalizedRootUri
        root!!.isDirectory = true
    }

    @Synchronized
    fun createFile(filepath: String, name: String): Boolean {
        val node = resolvePath(filepath) ?: return false
        if (!node.isDirectory) return false
        if (!node.loaded) structTree(node)
        try {
            val filename = URLDecoder.decode(name, FileUtil.DECODE_METHOD)
            if (node.findChild(filename) != null) return true
            val createdFile = FileUtil.createFile(node.uri.toString(), name) ?: return false
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
            val createdDirectory = FileUtil.createDir(node.uri.toString(), name) ?: return false
            val document = DocumentsNode(createdDirectory, true)
            document.parent = node
            node.addChild(document)
            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot create file, error: " + e.message)
        }
    }

    @Synchronized
    fun openContentUri(filepath: String, openMode: String): Int {
        val node = resolvePath(filepath) ?: return -1
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
            FileUtil.getFileSize(node.uri.toString())
        }
    }

    @Synchronized
    fun getUri(filepath: String): Uri {
        val node = resolvePath(filepath) ?: return Uri.EMPTY
        return node.uri ?: return Uri.EMPTY
    }

    @Synchronized
    fun folderUriHelper(path: String, createIfNotExists: Boolean = false): Uri? {
        root ?: return null
        val components = path.split(DELIMITER).filter { it.isNotEmpty() }
        var current = root

        for (component in components) {
            if (!current!!.loaded) {
                structTree(current)
            }

            var child = current.findChild(component)

            // Create directory if it doesn't exist and creation is enabled
            if (child == null && createIfNotExists) {
                try {
                    val createdDir =
                        FileUtil.createDir(current.uri.toString(), component) ?: return null
                    child = DocumentsNode(createdDir, true).apply {
                        parent = current
                    }
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
        return current?.uri
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
        try {
            val destinationParentUri = destinationNode.uri!!
            val destinationParent = if (destinationParentUri.scheme == "file") {
                DocumentFile.fromFile(File(destinationParentUri.path!!))
            } else {
                DocumentFile.fromTreeUri(context, destinationParentUri)
            } ?: return false
            val filename = URLDecoder.decode(destinationFilename, "UTF-8")
            val destination = destinationParent.createFile(
                "application/octet-stream",
                filename
            ) ?: return false
            val document = DocumentsNode()
            document.uri = destination.uri
            document.parent = destinationNode
            document.name = destination.name!!
            document.isDirectory = destination.isDirectory
            document.loaded = true
            with(FileUtil) {
                sourceNode.uri!!.inputStream().use { input ->
                    destination.uri.outputStream().use { output -> input.copyTo(output) }
                }
            }
            destinationNode.addChild(document)
            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot copy file, error: " + e.message)
        }
    }

    @Synchronized
    fun renameFile(filepath: String, destinationFilename: String): Boolean {
        val node = resolvePath(filepath) ?: return false
        try {
            val filename = URLDecoder.decode(destinationFilename, FileUtil.DECODE_METHOD)
            val nodeUri = node.uri!!
            if (nodeUri.scheme == "file") {
                val source = File(nodeUri.path!!)
                val target = File(source.parentFile, filename)
                if (!source.renameTo(target)) return false
                node.rename(filename, Uri.fromFile(target))
            } else {
                val newUri = DocumentsContract.renameDocument(
                    context.contentResolver,
                    nodeUri,
                    filename
                )
                node.rename(filename, newUri)
            }
            return true
        } catch (e: Exception) {
            error("[DocumentsTree]: Cannot rename file, error: " + e.message)
        }
    }

    @Synchronized
    fun moveFile(filename: String, sourceDirPath: String, destDirPath: String): Boolean {
        val sourceFileNode = resolvePath(sourceDirPath + "/" + filename) ?: return false
        val sourceDirNode = resolvePath(sourceDirPath) ?: return false
        val destDirNode = resolvePath(destDirPath) ?: return false
        try {
            val sourceUri = sourceFileNode.uri!!
            val sourceDirectoryUri = sourceDirNode.uri!!
            val destinationDirectoryUri = destDirNode.uri!!
            if (sourceUri.scheme == "file" && destinationDirectoryUri.scheme == "file") {
                val source = File(sourceUri.path!!)
                val destination = File(destinationDirectoryUri.path!!, filename)
                if (!source.renameTo(destination)) return false
                setRoot(root?.uri)
                return true
            }
            DocumentsContract.moveDocument(
                context.contentResolver,
                sourceUri,
                sourceDirectoryUri,
                destinationDirectoryUri
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
            val nodeUri = node.uri!!
            val deleted = if (nodeUri.scheme == "file") {
                File(nodeUri.path!!).delete()
            } else {
                DocumentsContract.deleteDocument(context.contentResolver, nodeUri)
            }
            if (!deleted) return false
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

        @get:Synchronized
        @set:Synchronized
        var loaded = false
        var isDirectory = false

        constructor()
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
