package com.example.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.core.DumpProcessor
import com.example.core.ExtractionProgressCallback
import com.example.core.InspectedDump
import com.example.core.crypto.KeySet
import com.example.core.crypto.KeyValidationSummary
import com.example.core.io.SafSeekableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.IOException

class DumpRepository(
    private val context: Context,
    private val prefs: PreferencesRepository
) {

    suspend fun loadKeySet(config: SetupConfig): KeySet? = withContext(Dispatchers.IO) {
        val prodUri = config.prodKeysUri?.let { Uri.parse(it) } ?: return@withContext null
        val titleUri = config.titleKeysUri?.let { Uri.parse(it) }
        val resolver = context.contentResolver

        val prodStream = resolver.openInputStream(prodUri)
            ?: throw IOException("Unable to read prod.keys from selected URI.")
        val titleStream = titleUri?.let { runCatching { resolver.openInputStream(it) }.getOrNull() }

        prodStream.use { p ->
            if (titleStream != null) {
                titleStream.use { t -> KeySet.parse(p, t) }
            } else {
                KeySet.parse(p, null)
            }
        }
    }

    suspend fun validateKeys(config: SetupConfig): KeyValidationSummary = withContext(Dispatchers.IO) {
        runCatching { loadKeySet(config)?.summarize() }
            .getOrNull()
            ?: KeyValidationSummary(
                isLoaded = false,
                warnings = if (config.prodKeysUri == null) emptyList() else listOf("Failed to read prod.keys file.")
            )
    }

    suspend fun scanLibrary(config: SetupConfig): CachedLibraryPayload = withContext(Dispatchers.IO) {
        val keys = loadKeySet(config) ?: KeySet()
        val gameDirUri = config.gameDirUri?.let { Uri.parse(it) }
        val collectedFiles = mutableListOf<Triple<DocumentFile, Boolean, Long>>()

        if (gameDirUri != null) {
            val tree = DocumentFile.fromTreeUri(context, gameDirUri)
            if (tree != null && tree.exists()) {
                collectDumpFiles(tree, depth = 0, out = collectedFiles)
            }
        }

        for (looseStr in config.looseFileUris) {
            val uri = Uri.parse(looseStr)
            val doc = DocumentFile.fromSingleUri(context, uri)
            if (doc != null && doc.exists()) {
                collectedFiles += Triple(doc, true, doc.length())
            }
        }

        // Deduplicate by URI string
        val uniqueFiles = collectedFiles.distinctBy { it.first.uri.toString() }

        val baseEntries = java.util.Collections.synchronizedList(mutableListOf<Pair<IndexedGame, String>>())
        val updateEntries = java.util.Collections.synchronizedList(mutableListOf<Pair<AttachedUpdate, String>>())
        val unsupported = java.util.Collections.synchronizedList(mutableListOf<UnsupportedFileEntry>())
        val scanUsable = keys.summarize().isUsableForExtraction
        val scanSem = Semaphore(4)

        coroutineScope {
            val tasks = uniqueFiles.map { (doc, isLoose, fileSize) ->
                async(Dispatchers.IO) {
                    val name = doc.name ?: "unknown"
                    val unsupportedExtReason = DumpProcessor.unsupportedReasonForName(name)
                    if (unsupportedExtReason != null) {
                        unsupported += UnsupportedFileEntry(
                            fileName = name,
                            uriString = doc.uri.toString(),
                            reason = unsupportedExtReason,
                            fileSizeBytes = fileSize
                        )
                        return@async
                    }

                    DumpProcessor.detectFormatByName(name) ?: return@async
                    if (!scanUsable) {
                        unsupported += UnsupportedFileEntry(
                            fileName = name,
                            uriString = doc.uri.toString(),
                            reason = "Cannot decrypt header: valid prod.keys with header_key is required.",
                            fileSizeBytes = fileSize
                        )
                        return@async
                    }

                    scanSem.acquire()
                    try {
                        val inspected: InspectedDump = SafSeekableSource.open(context.contentResolver, doc.uri).use { src ->
                            DumpProcessor.inspect(src, name, keys)
                        }

                        if (inspected.isDlc) {
                            unsupported += UnsupportedFileEntry(
                                fileName = name,
                                uriString = doc.uri.toString(),
                                reason = "DLC (AddOnContent) extraction is not supported.",
                                fileSizeBytes = fileSize
                            )
                            return@async
                        }

                        if (inspected.isUpdatePatch) {
                            updateEntries += AttachedUpdate(
                                uriString = doc.uri.toString(),
                                fileName = name,
                                format = inspected.format,
                                titleIdHex = inspected.titleIdHex,
                                displayVersion = inspected.displayVersion,
                                versionCode = inspected.versionCode,
                                fileSizeBytes = fileSize,
                                hasBktr = inspected.hasBktr,
                                isLooseFile = isLoose
                            ) to inspected.baseTitleIdHex
                        } else {
                            val iconPath = inspected.iconBytes?.let {
                                SafStorageHelper.saveIconToCache(context, inspected.baseTitleIdHex, it)
                            }
                            baseEntries += IndexedGame(
                                id = doc.uri.toString(),
                                uriString = doc.uri.toString(),
                                fileName = name,
                                format = inspected.format,
                                titleIdHex = inspected.baseTitleIdHex,
                                titleName = inspected.titleName,
                                publisher = inspected.publisher,
                                displayVersion = inspected.displayVersion,
                                versionCode = inspected.versionCode,
                                fileSizeBytes = fileSize,
                                hasExeFs = inspected.hasExeFs,
                                hasRomFs = inspected.hasRomFs,
                                exeFsSizeBytes = inspected.exeFsSizeBytes,
                                romFsSizeBytes = inspected.romFsSizeBytes,
                                iconCachePath = iconPath,
                                ncaSummary = inspected.ncaSummary,
                                matchedUpdates = emptyList(),
                                isLooseFile = isLoose,
                                isUpdateOnlyWithoutBase = false
                            ) to inspected.baseTitleIdHex
                        }
                    } catch (e: Exception) {
                        unsupported += UnsupportedFileEntry(
                            fileName = name,
                            uriString = doc.uri.toString(),
                            reason = e.message ?: "Failed to parse dump",
                            fileSizeBytes = fileSize
                        )
                    } finally {
                        scanSem.release()
                    }
                }
            }
            tasks.forEach { it.await() }
        }

        val updatesByBase = updateEntries.groupBy({ it.second }, { it.first })
        val games = baseEntries.map { (game, baseTid) ->
            val attached = updatesByBase[baseTid]
                ?.sortedByDescending { it.versionCode }
                ?: emptyList()
            game.copy(matchedUpdates = attached)
        }.sortedBy { it.titleName.lowercase() }.toMutableList()

        // Also surface orphan updates so user sees loose update files even if base isn't indexed yet
        val matchedBaseTids = baseEntries.map { it.second }.toSet()
        for ((baseTid, orphanUpdates) in updatesByBase) {
            if (baseTid !in matchedBaseTids) {
                for (upd in orphanUpdates) {
                    games += IndexedGame(
                        id = upd.uriString,
                        uriString = upd.uriString,
                        fileName = upd.fileName,
                        format = upd.format,
                        titleIdHex = baseTid,
                        titleName = "${upd.fileName.substringBeforeLast('.')} (Update Only)",
                        publisher = "Requires Base Dump to Merge",
                        displayVersion = upd.displayVersion,
                        versionCode = upd.versionCode,
                        fileSizeBytes = upd.fileSizeBytes,
                        hasExeFs = false,
                        hasRomFs = false,
                        exeFsSizeBytes = 0L,
                        romFsSizeBytes = 0L,
                        matchedUpdates = listOf(upd),
                        isLooseFile = upd.isLooseFile,
                        isUpdateOnlyWithoutBase = true
                    )
                }
            }
        }

        val payload = CachedLibraryPayload(
            games = games,
            unsupported = unsupported,
            lastScannedEpochMs = System.currentTimeMillis()
        )
        prefs.saveCachedLibrary(payload)
        payload
    }

    suspend fun runExtraction(
        config: SetupConfig,
        request: ExtractionRequest,
        callbacks: ExtractionProgressCallback
    ): String = withContext(Dispatchers.IO) {
        val keys = loadKeySet(config)
            ?: throw IOException("prod.keys is not configured.")
        val outputTreeUri = config.outputDirUri?.let { Uri.parse(it) }
            ?: throw IOException("Output folder is not configured.")
        val outputRoot = DocumentFile.fromTreeUri(context, outputTreeUri)
            ?: throw IOException("Unable to access output folder.")

        val safeTitleFolder = request.game.titleName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { request.game.titleIdHex }
        val versionSuffix = if (request.mergeUpdate && request.selectedUpdate != null) {
            "_v${request.selectedUpdate.displayVersion}"
        } else {
            "_v${request.game.displayVersion}"
        }
        val targetFolderName = "${safeTitleFolder} [${request.game.titleIdHex}]$versionSuffix"

        val existingDir = outputRoot.findFile(targetFolderName)?.takeIf { it.isDirectory }
        val isNewDir = existingDir == null
        val gameOutDir = existingDir
            ?: outputRoot.createDirectory(targetFolderName)
            ?: throw IOException("Failed to create output folder '$targetFolderName'")

        val sink = SafExtractionSink(context, gameOutDir, assumeRootEmpty = isNewDir)
        val baseUri = Uri.parse(request.game.uriString)
        val updateUri = if (request.mergeUpdate) request.selectedUpdate?.uriString?.let { Uri.parse(it) } else null

        SafSeekableSource.open(context.contentResolver, baseUri).use { baseSrc ->
            if (updateUri != null && request.selectedUpdate != null) {
                SafSeekableSource.open(context.contentResolver, updateUri).use { updSrc ->
                    DumpProcessor.extract(
                        baseSource = baseSrc,
                        baseFormat = request.game.format,
                        updateSource = updSrc,
                        updateFormat = request.selectedUpdate.format,
                        target = request.target,
                        keys = keys,
                        sink = sink,
                        callbacks = callbacks,
                        baseFileName = request.game.fileName,
                        updateFileName = request.selectedUpdate.fileName
                    )
                }
            } else {
                DumpProcessor.extract(
                    baseSource = baseSrc,
                    baseFormat = request.game.format,
                    updateSource = null,
                    updateFormat = null,
                    target = request.target,
                    keys = keys,
                    sink = sink,
                    callbacks = callbacks,
                    baseFileName = request.game.fileName
                )
            }
        }
        "${config.outputDirName ?: "Output"}/$targetFolderName"
    }

    private fun collectDumpFiles(
        dir: DocumentFile,
        depth: Int,
        out: MutableList<Triple<DocumentFile, Boolean, Long>>
    ) {
        if (depth > 3) return
        val children = dir.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                collectDumpFiles(child, depth + 1, out)
            } else if (child.isFile) {
                val name = child.name ?: continue
                if (DumpProcessor.detectFormatByName(name) != null ||
                    DumpProcessor.unsupportedReasonForName(name) != null
                ) {
                    out += Triple(child, false, child.length())
                }
            }
        }
    }
}
