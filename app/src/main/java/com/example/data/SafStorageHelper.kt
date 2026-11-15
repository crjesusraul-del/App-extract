package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.core.ExtractionSink
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

object SafStorageHelper {

    fun takePersistableReadPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun takePersistableReadWritePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        val doc = DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
        doc?.name?.let { if (it.isNotBlank()) return it }

        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!treeId.isNullOrBlank()) {
            return treeId.substringAfterLast(':').ifBlank { treeId }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Selected Item"
    }

    /**
     * Checks Android 11+ scoped storage restriction: selecting internal storage root ("primary:")
     * or the top-level "Download" folder ("primary:Download") is often restricted for tree creation.
     */
    fun validateOutputTreeUri(context: Context, uri: Uri): String? {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (treeId != null) {
            val pathPart = treeId.substringAfter(':', "").trim('/')
            if (pathPart.isEmpty() || pathPart.equals("Download", ignoreCase = true) || pathPart.equals("Downloads", ignoreCase = true)) {
                return "Android 11+ restricts writing directly to the storage root or top-level Download folder. Choose or create a dedicated subfolder."
            }
        }
        val docTree = DocumentFile.fromTreeUri(context, uri)
        if (docTree == null || !docTree.canWrite()) {
            return "Selected output folder is not writable. Please pick a dedicated folder with write access."
        }
        return null
    }

    fun saveIconToCache(context: Context, titleIdHex: String, bytes: ByteArray): String? {
        return runCatching {
            val dir = File(context.cacheDir, "icons").apply { mkdirs() }
            val file = File(dir, "$titleIdHex.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }
}

private data class SafChildNode(
    val uri: Uri,
    val isDirectory: Boolean
)

private class SafDirState(
    val uri: Uri,
    @Volatile var childrenLoaded: Boolean,
    val children: HashMap<String, SafChildNode> = HashMap()
) {
    val lock = Any()
}

class SafExtractionSink(
    private val context: Context,
    rootDir: DocumentFile,
    assumeRootEmpty: Boolean = false
) : ExtractionSink {

    override val supportsParallelWrites: Boolean = true

    private val resolver = context.contentResolver
    private val treeRootUri = rootDir.uri
    private val dirStates = ConcurrentHashMap<String, SafDirState>().apply {
        put("", SafDirState(rootDir.uri, childrenLoaded = assumeRootEmpty))
    }

    override fun writeFile(relativePath: String, totalSize: Long, writer: (OutputStream) -> Unit) {
        val normalized = relativePath.trimStart('/')
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return

        val parentPathParts = parts.dropLast(1)
        val fileName = parts.last()
        val parentState = ensureDirectory(parentPathParts)

        val existingToDelete: SafChildNode? = synchronized(parentState.lock) {
            loadChildrenIfNeededLocked(parentState)
            parentState.children.remove(fileName)
        }

        if (existingToDelete != null) {
            runCatching { DocumentsContract.deleteDocument(resolver, existingToDelete.uri) }
        }

        val targetUri = createDocumentCompat(
            parentUri = parentState.uri,
            mimeType = "application/octet-stream",
            displayName = fileName
        ) ?: throw IOException("Failed to create output file: $normalized")

        val os = resolver.openOutputStream(targetUri, "w")
            ?: throw IOException("Failed to open output stream for: $normalized")
        os.use { stream ->
            writer(stream)
        }
    }

    private fun ensureDirectory(segments: List<String>): SafDirState {
        var currentKey = ""
        var currentState = dirStates.getValue("")

        for (segment in segments) {
            val nextKey = if (currentKey.isEmpty()) segment else "$currentKey/$segment"
            val fastHit = dirStates[nextKey]
            if (fastHit != null) {
                currentState = fastHit
                currentKey = nextKey
                continue
            }

            val nextState = synchronized(currentState.lock) {
                dirStates[nextKey]?.let { return@synchronized it }
                loadChildrenIfNeededLocked(currentState)
                val existingChild = currentState.children[segment]
                val resolved = if (existingChild != null && existingChild.isDirectory) {
                    SafDirState(existingChild.uri, childrenLoaded = false)
                } else {
                    if (existingChild != null) {
                        runCatching { DocumentsContract.deleteDocument(resolver, existingChild.uri) }
                    }
                    val newDirUri = createDocumentCompat(
                        parentUri = currentState.uri,
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                        displayName = segment
                    ) ?: throw IOException("Unable to create directory '$segment' in output tree")
                    currentState.children[segment] = SafChildNode(newDirUri, isDirectory = true)
                    SafDirState(newDirUri, childrenLoaded = true)
                }
                dirStates[nextKey] = resolved
                resolved
            }
            currentState = nextState
            currentKey = nextKey
        }
        return currentState
    }

    private fun loadChildrenIfNeededLocked(state: SafDirState) {
        if (state.childrenLoaded) return
        state.childrenLoaded = true

        val queried = runCatching {
            val docId = DocumentsContract.getDocumentId(state.uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeRootUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val childId = if (idCol >= 0) cursor.getString(idCol) else null
                    val childName = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    if (!childId.isNullOrBlank() && !childName.isNullOrBlank()) {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, childId)
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        state.children[childName] = SafChildNode(childUri, isDir)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)

        if (!queried) {
            val fallbackDoc = DocumentFile.fromTreeUri(context, state.uri) ?: return
            for (child in fallbackDoc.listFiles()) {
                val name = child.name ?: continue
                state.children[name] = SafChildNode(child.uri, child.isDirectory)
            }
        }
    }

    private fun createDocumentCompat(parentUri: Uri, mimeType: String, displayName: String): Uri? {
        return runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mimeType, displayName)
        }.getOrNull() ?: run {
            val doc = DocumentFile.fromTreeUri(context, parentUri) ?: return null
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                doc.createDirectory(displayName)?.uri
            } else {
                doc.createFile(mimeType, displayName)?.uri
            }
        }
    }
}
