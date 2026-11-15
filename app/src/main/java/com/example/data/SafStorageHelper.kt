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

class SafExtractionSink(
    private val context: Context,
    private val rootDir: DocumentFile
) : ExtractionSink {

    private val dirCache = ConcurrentHashMap<String, DocumentFile>()

    override fun writeFile(relativePath: String, totalSize: Long, writer: (OutputStream) -> Unit) {
        val normalized = relativePath.trimStart('/')
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return

        val parentPathParts = parts.dropLast(1)
        val fileName = parts.last()
        val parentDir = ensureDirectory(parentPathParts)

        // Replace existing file if re-extracting
        parentDir.findFile(fileName)?.delete()
        val targetFile = parentDir.createFile("application/octet-stream", fileName)
            ?: throw IOException("Failed to create output file: $normalized")

        val os = context.contentResolver.openOutputStream(targetFile.uri, "w")
            ?: throw IOException("Failed to open output stream for: $normalized")
        os.use { stream ->
            writer(stream)
            stream.flush()
        }
    }

    private fun ensureDirectory(segments: List<String>): DocumentFile {
        if (segments.isEmpty()) return rootDir
        var current = rootDir
        val pathBuilder = StringBuilder()
        for (segment in segments) {
            if (pathBuilder.isNotEmpty()) pathBuilder.append('/')
            pathBuilder.append(segment)
            val key = pathBuilder.toString()
            val cached = dirCache[key]
            if (cached != null) {
                current = cached
            } else {
                val next = current.findFile(segment)?.takeIf { it.isDirectory }
                    ?: current.createDirectory(segment)
                    ?: throw IOException("Unable to create directory '$segment' in output tree")
                dirCache[key] = next
                current = next
            }
        }
        return current
    }
}
