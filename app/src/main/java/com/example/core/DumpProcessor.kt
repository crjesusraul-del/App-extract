package com.example.core

import com.example.core.crypto.KeySet
import com.example.core.crypto.SwitchCrypto
import com.example.core.formats.CnmtMetadata
import com.example.core.formats.CnmtParser
import com.example.core.formats.ContentMetaType
import com.example.core.formats.NacpMetadata
import com.example.core.formats.NacpParser
import com.example.core.formats.NcaContentType
import com.example.core.formats.NcaFile
import com.example.core.formats.NcaFsType
import com.example.core.formats.Pfs0Container
import com.example.core.formats.RomFsReader
import com.example.core.formats.TicketParser
import com.example.core.formats.XciContainer
import com.example.core.io.BinaryUtils
import com.example.core.io.BufferedSeekableSource
import com.example.core.io.SeekableSource
import com.example.data.DumpFormat
import com.example.data.ExtractionTarget
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface ExtractionSink {
    val supportsParallelWrites: Boolean get() = false
    fun writeFile(relativePath: String, totalSize: Long, writer: (OutputStream) -> Unit)
}

data class InspectedDump(
    val format: DumpFormat,
    val titleIdHex: String,
    val baseTitleIdHex: String,
    val titleName: String,
    val publisher: String,
    val displayVersion: String,
    val versionCode: Long,
    val isUpdatePatch: Boolean,
    val isDlc: Boolean,
    val hasExeFs: Boolean,
    val hasRomFs: Boolean,
    val exeFsSizeBytes: Long,
    val romFsSizeBytes: Long,
    val hasBktr: Boolean,
    val ncaSummary: List<String>,
    val iconBytes: ByteArray?
)

data class ExtractionProgressCallback(
    val onPhase: (String) -> Unit,
    val onFileProgress: (currentFile: String, filesDone: Int, totalFiles: Int, bytesDone: Long, totalBytes: Long) -> Unit,
    val isCancelled: () -> Boolean
)

object DumpProcessor {

    private const val COPY_BUFFER_SIZE = 2 * 1024 * 1024
    private const val READ_AHEAD_WINDOW_SIZE = 1024 * 1024
    private const val SMALL_FILE_THRESHOLD = 512L * 1024L
    private const val WRITER_POOL_THREADS = 4
    private const val MAX_IN_FLIGHT_SMALL_FILES = 24

    fun detectFormatByName(fileName: String): DumpFormat? {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".xci") -> DumpFormat.XCI
            lower.endsWith(".nsp") -> DumpFormat.NSP
            lower.endsWith(".nca") -> DumpFormat.NCA
            else -> null
        }
    }

    fun unsupportedReasonForName(fileName: String): String? {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".nsz") -> "Compressed NSZ format is not supported (requires uncompressed NSP)."
            lower.endsWith(".xcz") -> "Compressed XCZ format is not supported (requires uncompressed XCI)."
            else -> null
        }
    }

    fun inspect(source: SeekableSource, fileName: String, keys: KeySet): InspectedDump {
        val format = detectFormatByName(fileName)
            ?: throw IOException("Unsupported file extension: $fileName")

        val ncaSources = openNcasWithTickets(source, format, keys)
        var parsedCnmt: CnmtMetadata? = null
        var parsedNacp: NacpMetadata? = null
        var iconBytes: ByteArray? = null
        var programNca: NcaFile? = null
        val ncaSummaries = mutableListOf<String>()

        for ((name, ncaSlice) in ncaSources) {
            val nca = NcaFile.parse(ncaSlice, keys)
            val secDesc = nca.sections.joinToString(", ") {
                "${it.fsType.name}:${BinaryUtils.formatBytes(it.size)}"
            }
            ncaSummaries += "${nca.contentType.name} ($name) [$secDesc]"

            when (nca.contentType) {
                NcaContentType.PROGRAM -> {
                    if (programNca == null) programNca = nca
                }
                NcaContentType.META -> {
                    val pfsSec = nca.sections.firstOrNull { it.fsType == NcaFsType.PFS0 }
                    if (pfsSec != null && parsedCnmt == null) {
                        runCatching {
                            val secFs = nca.openSectionFilesystemSlice(pfsSec, keys)
                            val pfs = Pfs0Container.parse(secFs)
                            val cnmtEntry = pfs.entries.firstOrNull { it.name.endsWith(".cnmt", ignoreCase = true) }
                            if (cnmtEntry != null) {
                                val bytes = pfs.openEntry(cnmtEntry).readFully(0, cnmtEntry.size.toInt())
                                parsedCnmt = CnmtParser.parse(bytes)
                            }
                        }
                    }
                }
                NcaContentType.CONTROL -> {
                    val romSec = nca.sections.firstOrNull { it.fsType == NcaFsType.ROMFS }
                    if (romSec != null) {
                        runCatching {
                            val secFs = nca.openSectionFilesystemSlice(romSec, keys)
                            val romFs = RomFsReader.parse(secFs)
                            romFs.findFileByName("control.nacp")?.let { nacpEntry ->
                                parsedNacp = NacpParser.parse(romFs.readFileBytes(nacpEntry))
                            }
                            val iconEntry = romFs.files.firstOrNull {
                                it.relativePath.substringAfterLast('/').startsWith("icon_", ignoreCase = true) &&
                                    it.relativePath.endsWith(".dat", ignoreCase = true)
                            }
                            if (iconEntry != null && iconEntry.size <= 512 * 1024) {
                                iconBytes = romFs.readFileBytes(iconEntry)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }

        val rawTitleId = parsedCnmt?.titleId
            ?: programNca?.titleId
            ?: ncaSources.firstOrNull()?.let { NcaFile.parse(it.second, keys).titleId }
            ?: 0L
        val baseTid = BinaryUtils.baseTitleIdFromAny(rawTitleId)
        val isUpdate = parsedCnmt?.metaType == ContentMetaType.PATCH ||
            ((rawTitleId and 0xFFFL) == 0x800L) ||
            (programNca?.sections?.any { it.hasBktr } == true)
        val isDlc = parsedCnmt?.metaType == ContentMetaType.ADD_ON_CONTENT

        val fallbackTitle = fileName.substringBeforeLast('.').ifBlank { BinaryUtils.formatTitleId(baseTid) }
        val activeNca = programNca ?: ncaSources.firstOrNull()?.let { NcaFile.parse(it.second, keys) }

        val exeFsSize = activeNca?.sections?.firstOrNull { it.fsType == NcaFsType.PFS0 }?.dataSubSize ?: 0L
        val romFsSize = activeNca?.sections?.firstOrNull { it.fsType == NcaFsType.ROMFS }?.dataSubSize ?: 0L
        val hasBktr = activeNca?.sections?.any { it.hasBktr } == true

        return InspectedDump(
            format = format,
            titleIdHex = BinaryUtils.formatTitleId(rawTitleId),
            baseTitleIdHex = BinaryUtils.formatTitleId(baseTid),
            titleName = parsedNacp?.title?.takeIf { it.isNotBlank() && it != "Unknown Title" } ?: fallbackTitle,
            publisher = parsedNacp?.publisher ?: "Unknown Publisher",
            displayVersion = parsedNacp?.displayVersion ?: parsedCnmt?.formattedVersion ?: "1.0.0",
            versionCode = parsedCnmt?.version ?: 0L,
            isUpdatePatch = isUpdate,
            isDlc = isDlc,
            hasExeFs = exeFsSize > 0L || activeNca?.sections?.any { it.fsType == NcaFsType.PFS0 } == true,
            hasRomFs = romFsSize > 0L || activeNca?.sections?.any { it.fsType == NcaFsType.ROMFS } == true,
            exeFsSizeBytes = exeFsSize,
            romFsSizeBytes = romFsSize,
            hasBktr = hasBktr,
            ncaSummary = ncaSummaries,
            iconBytes = iconBytes
        )
    }

    fun extract(
        baseSource: SeekableSource,
        baseFormat: DumpFormat,
        updateSource: SeekableSource?,
        updateFormat: DumpFormat?,
        target: ExtractionTarget,
        keys: KeySet,
        sink: ExtractionSink,
        callbacks: ExtractionProgressCallback,
        baseFileName: String = "content.nca",
        updateFileName: String = "update.nca"
    ) {
        callbacks.onPhase("Parsing container & NCA headers...")
        val baseNcas = openNcasWithTickets(baseSource, baseFormat, keys, baseFileName)
        val updateNcas = if (updateSource != null && updateFormat != null) {
            openNcasWithTickets(updateSource, updateFormat, keys, updateFileName)
        } else {
            emptyList()
        }

        val extractExeFs = target == ExtractionTarget.EXEFS || target == ExtractionTarget.BOTH || target == ExtractionTarget.ALL
        val extractRomFs = target == ExtractionTarget.ROMFS || target == ExtractionTarget.BOTH || target == ExtractionTarget.ALL
        val extractNcas = target == ExtractionTarget.NCA || target == ExtractionTarget.ALL

        val baseProgram = if (extractExeFs || extractRomFs) {
            findProgramNca(baseNcas, keys)
                ?: throw IOException("No NCA found inside base dump.")
        } else null

        val updateProgram: NcaFile? = if ((extractExeFs || extractRomFs) && updateNcas.isNotEmpty()) {
            findProgramNca(updateNcas, keys)
        } else {
            null
        }

        // Select ExeFS source (if update provides a full ExeFS PFS0, prefer update's ExeFS)
        val exeFsNca = if (extractExeFs && baseProgram != null) {
            val updExe = updateProgram?.takeIf { nca -> nca.sections.any { it.fsType == NcaFsType.PFS0 } }
            updExe ?: baseProgram
        } else null

        val exeFsSection = exeFsNca?.sections?.firstOrNull { it.fsType == NcaFsType.PFS0 }
        val baseRomFsSection = baseProgram?.sections?.firstOrNull { it.fsType == NcaFsType.ROMFS }
        val updateRomFsSection = updateProgram?.sections?.firstOrNull { it.fsType == NcaFsType.ROMFS }

        val exeFsPfs0 = if (extractExeFs && exeFsNca != null && exeFsSection != null) {
            callbacks.onPhase("Indexing ExeFS partition...")
            val exeSlice = BufferedSeekableSource(
                exeFsNca.openSectionFilesystemSlice(exeFsSection, keys),
                windowSize = READ_AHEAD_WINDOW_SIZE
            )
            Pfs0Container.parse(exeSlice)
        } else null

        val romFsReader = if (extractRomFs && baseProgram != null) {
            callbacks.onPhase(if (updateRomFsSection != null) "Indexing merged BKTR RomFS..." else "Indexing RomFS...")
            val rawRomSource = when {
                updateProgram != null && updateRomFsSection != null && updateRomFsSection.hasBktr -> {
                    val baseSec = baseRomFsSection
                        ?: throw IOException("Base dump has no RomFS section to apply BKTR patch onto.")
                    val baseSlice = baseProgram.openSectionFilesystemSlice(baseSec, keys)
                    updateProgram.openSectionFilesystemSlice(updateRomFsSection, keys, baseSlice)
                }
                updateProgram != null && updateRomFsSection != null -> {
                    updateProgram.openSectionFilesystemSlice(updateRomFsSection, keys)
                }
                baseRomFsSection != null -> {
                    baseProgram.openSectionFilesystemSlice(baseRomFsSection, keys)
                }
                else -> throw IOException("No RomFS section found in dump.")
            }
            val bufferedRomSource = BufferedSeekableSource(rawRomSource, windowSize = READ_AHEAD_WINDOW_SIZE)
            RomFsReader.parse(bufferedRomSource)
        } else null

        val rawNcaPlan = if (extractNcas) {
            buildList {
                baseNcas.forEach { (name, src) -> add("nca/$name" to src) }
                updateNcas.forEach { (name, src) -> add("nca/update/$name" to src) }
            }
        } else {
            emptyList()
        }

        val totalFiles = (exeFsPfs0?.entries?.size ?: 0) +
            (romFsReader?.files?.size ?: 0) +
            rawNcaPlan.size
        val totalBytes = (exeFsPfs0?.entries?.sumOf { it.size } ?: 0L) +
            (romFsReader?.totalSizeBytes ?: 0L) +
            rawNcaPlan.sumOf { it.second.length }

        if (totalFiles == 0) {
            throw IOException("Selected target ($target) contains 0 extractable files.")
        }

        if (sink.supportsParallelWrites) {
            extractParallel(
                rawNcaPlan = rawNcaPlan,
                exeFsPfs0 = exeFsPfs0,
                romFsReader = romFsReader,
                totalFiles = totalFiles,
                totalBytes = totalBytes,
                updateRomFsActive = updateRomFsSection != null,
                sink = sink,
                callbacks = callbacks
            )
        } else {
            extractSequential(
                rawNcaPlan = rawNcaPlan,
                exeFsPfs0 = exeFsPfs0,
                romFsReader = romFsReader,
                totalFiles = totalFiles,
                totalBytes = totalBytes,
                updateRomFsActive = updateRomFsSection != null,
                sink = sink,
                callbacks = callbacks
            )
        }
    }

    private fun extractSequential(
        rawNcaPlan: List<Pair<String, SeekableSource>>,
        exeFsPfs0: Pfs0Container?,
        romFsReader: RomFsReader?,
        totalFiles: Int,
        totalBytes: Long,
        updateRomFsActive: Boolean,
        sink: ExtractionSink,
        callbacks: ExtractionProgressCallback
    ) {
        val copyBuf = ByteArray(COPY_BUFFER_SIZE)
        var filesDone = 0
        var bytesDone = 0L

        if (rawNcaPlan.isNotEmpty()) {
            callbacks.onPhase("Extracting raw NCAs...")
            for ((outPath, ncaSrc) in rawNcaPlan) {
                if (callbacks.isCancelled()) throw InterruptedException("Extraction cancelled")
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                sink.writeFile(outPath, ncaSrc.length) { os ->
                    copyFromSeekable(ncaSrc, os, ncaSrc.length, copyBuf, callbacks) { delta ->
                        bytesDone += delta
                        callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                    }
                }
                filesDone++
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
            }
        }

        if (exeFsPfs0 != null) {
            callbacks.onPhase("Extracting ExeFS...")
            for (entry in exeFsPfs0.entries) {
                if (callbacks.isCancelled()) throw InterruptedException("Extraction cancelled")
                val outPath = "exefs/${entry.name}"
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                val entrySource = exeFsPfs0.openEntry(entry)
                sink.writeFile(outPath, entry.size) { os ->
                    copyFromSeekable(entrySource, os, entry.size, copyBuf, callbacks) { delta ->
                        bytesDone += delta
                        callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                    }
                }
                filesDone++
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
            }
        }

        if (romFsReader != null) {
            callbacks.onPhase(if (updateRomFsActive) "Extracting Merged RomFS..." else "Extracting RomFS...")
            val orderedFiles = romFsReader.files.sortedBy { it.dataOffset }
            for (entry in orderedFiles) {
                if (callbacks.isCancelled()) throw InterruptedException("Extraction cancelled")
                val outPath = "romfs/${entry.relativePath.trimStart('/')}"
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                val entrySource = romFsReader.openFileSource(entry)
                sink.writeFile(outPath, entry.size) { os ->
                    copyFromSeekable(entrySource, os, entry.size, copyBuf, callbacks) { delta ->
                        bytesDone += delta
                        callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                    }
                }
                filesDone++
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
            }
        }
    }

    /**
     * Multi-core pipelined extraction optimized for 8-core SoCs & UFS 2.2 storage:
     * - Small files (<= 512 KB) are pre-read sequentially in RAM from the 1 MB read-ahead window
     *   and dispatched across 4 concurrent SAF writer threads to hide Binder/FUSE IPC latency.
     * - Large files (> 512 KB) stream through a 2-slot double-buffered async pipe (2 MB chunks)
     *   so AES-CTR decryption and UFS 2.2 writes overlap on separate CPU cores.
     */
    private fun extractParallel(
        rawNcaPlan: List<Pair<String, SeekableSource>>,
        exeFsPfs0: Pfs0Container?,
        romFsReader: RomFsReader?,
        totalFiles: Int,
        totalBytes: Long,
        updateRomFsActive: Boolean,
        sink: ExtractionSink,
        callbacks: ExtractionProgressCallback
    ) {
        val threadCounter = AtomicInteger(0)
        val writerPool = Executors.newFixedThreadPool(WRITER_POOL_THREADS) { r ->
            Thread(r, "nxdump-io-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        }
        val inFlightPermits = Semaphore(MAX_IN_FLIGHT_SMALL_FILES)
        val pendingFutures = ArrayDeque<Future<*>>(64)
        val firstError = AtomicReference<Throwable?>(null)
        val filesDoneCounter = AtomicInteger(0)
        val bytesDoneCounter = AtomicLong(0L)

        fun checkStatus() {
            firstError.get()?.let { throw it }
            if (callbacks.isCancelled()) throw InterruptedException("Extraction cancelled")
        }

        fun drainWriters() {
            while (pendingFutures.isNotEmpty()) {
                pendingFutures.removeFirst().get()
            }
            checkStatus()
        }

        fun submitSmallFile(outPath: String, src: SeekableSource, size: Long) {
            checkStatus()
            val payload = if (size == 0L) ByteArray(0) else src.readFully(0, size.toInt())
            inFlightPermits.acquire()
            val future = writerPool.submit(Callable {
                try {
                    if (callbacks.isCancelled() || firstError.get() != null) return@Callable
                    sink.writeFile(outPath, size) { os ->
                        if (payload.isNotEmpty()) os.write(payload)
                    }
                    val doneFiles = filesDoneCounter.incrementAndGet()
                    val doneBytes = bytesDoneCounter.addAndGet(size)
                    callbacks.onFileProgress(outPath, doneFiles, totalFiles, doneBytes, totalBytes)
                } catch (t: Throwable) {
                    firstError.compareAndSet(null, t)
                } finally {
                    inFlightPermits.release()
                }
            })
            pendingFutures.addLast(future)
            if (pendingFutures.size >= 48) {
                while (pendingFutures.size > 16) {
                    pendingFutures.removeFirst().get()
                }
                checkStatus()
            }
        }

        fun writeLargeFileDoubleBuffered(outPath: String, src: SeekableSource, size: Long) {
            drainWriters()
            val freeBuffers = ArrayBlockingQueue<ByteArray>(2).apply {
                put(ByteArray(COPY_BUFFER_SIZE))
                put(ByteArray(COPY_BUFFER_SIZE))
            }
            val filledQueue = ArrayBlockingQueue<Pair<ByteArray, Int>>(2)
            val writerError = AtomicReference<Throwable?>(null)

            val writerFuture = writerPool.submit(Callable {
                try {
                    sink.writeFile(outPath, size) { os ->
                        while (true) {
                            val (buf, len) = filledQueue.take()
                            if (len < 0) break
                            os.write(buf, 0, len)
                            freeBuffers.put(buf)
                            val curBytes = bytesDoneCounter.addAndGet(len.toLong())
                            callbacks.onFileProgress(
                                outPath,
                                filesDoneCounter.get(),
                                totalFiles,
                                curBytes,
                                totalBytes
                            )
                        }
                    }
                } catch (t: Throwable) {
                    writerError.compareAndSet(null, t)
                    // Unblock producer if waiting on freeBuffers
                    freeBuffers.offer(ByteArray(0))
                }
            })

            try {
                var offset = 0L
                while (offset < size) {
                    if (callbacks.isCancelled()) throw InterruptedException("Extraction cancelled")
                    writerError.get()?.let { throw it }

                    val buf = freeBuffers.take()
                    if (buf.isEmpty()) {
                        writerError.get()?.let { throw it }
                        throw IOException("Writer aborted unexpectedly")
                    }
                    val toRead = minOf(buf.size.toLong(), size - offset).toInt()
                    var readTotal = 0
                    while (readTotal < toRead) {
                        val n = src.readAt(offset + readTotal, buf, readTotal, toRead - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    if (readTotal <= 0) {
                        freeBuffers.offer(buf)
                        break
                    }
                    offset += readTotal
                    filledQueue.put(buf to readTotal)
                }
                filledQueue.put(ByteArray(0) to -1)
                writerFuture.get()
                writerError.get()?.let { throw it }
                val doneFiles = filesDoneCounter.incrementAndGet()
                callbacks.onFileProgress(outPath, doneFiles, totalFiles, bytesDoneCounter.get(), totalBytes)
            } catch (t: Throwable) {
                filledQueue.offer(ByteArray(0) to -1)
                runCatching { writerFuture.get() }
                throw t
            }
        }

        fun dispatchEntry(outPath: String, src: SeekableSource, size: Long) {
            if (size <= SMALL_FILE_THRESHOLD) {
                submitSmallFile(outPath, src, size)
            } else {
                writeLargeFileDoubleBuffered(outPath, src, size)
            }
        }

        try {
            if (rawNcaPlan.isNotEmpty()) {
                callbacks.onPhase("Extracting raw NCAs...")
                for ((outPath, ncaSrc) in rawNcaPlan) {
                    dispatchEntry(outPath, ncaSrc, ncaSrc.length)
                }
            }

            if (exeFsPfs0 != null) {
                callbacks.onPhase("Extracting ExeFS...")
                for (entry in exeFsPfs0.entries) {
                    dispatchEntry("exefs/${entry.name}", exeFsPfs0.openEntry(entry), entry.size)
                }
            }

            if (romFsReader != null) {
                callbacks.onPhase(if (updateRomFsActive) "Extracting Merged RomFS..." else "Extracting RomFS...")
                val orderedFiles = romFsReader.files.sortedBy { it.dataOffset }
                for (entry in orderedFiles) {
                    val outPath = "romfs/${entry.relativePath.trimStart('/')}"
                    dispatchEntry(outPath, romFsReader.openFileSource(entry), entry.size)
                }
            }

            drainWriters()
        } finally {
            writerPool.shutdownNow()
        }
    }

    private fun copyFromSeekable(
        source: SeekableSource,
        os: OutputStream,
        totalLen: Long,
        buf: ByteArray,
        callbacks: ExtractionProgressCallback,
        onBytesDelta: (Long) -> Unit
    ) {
        var offset = 0L
        while (offset < totalLen) {
            if (callbacks.isCancelled()) throw InterruptedException("Extraction cancelled")
            val chunk = minOf(buf.size.toLong(), totalLen - offset).toInt()
            val n = source.readAt(offset, buf, 0, chunk)
            if (n <= 0) break
            os.write(buf, 0, n)
            offset += n
            onBytesDelta(n.toLong())
        }
    }

    private fun findProgramNca(ncas: List<Pair<String, SeekableSource>>, keys: KeySet): NcaFile? {
        for ((_, slice) in ncas) {
            val nca = NcaFile.parse(slice, keys)
            if (nca.contentType == NcaContentType.PROGRAM) {
                return nca
            }
        }
        return ncas.firstOrNull()?.let { NcaFile.parse(it.second, keys) }
    }

    private fun openNcasWithTickets(
        source: SeekableSource,
        format: DumpFormat,
        keys: KeySet,
        singleNcaName: String = "content.nca"
    ): List<Pair<String, SeekableSource>> {
        return when (format) {
            DumpFormat.NCA -> {
                val safeName = if (singleNcaName.lowercase(Locale.US).endsWith(".nca")) singleNcaName else "$singleNcaName.nca"
                listOf(safeName to source)
            }
            DumpFormat.NSP -> {
                val pfs = Pfs0Container.parse(source)
                ingestTickets(pfs.entries.filter { it.name.endsWith(".tik", ignoreCase = true) }, keys) { entry ->
                    pfs.openEntry(entry)
                }
                pfs.entries
                    .filter { it.name.endsWith(".nca", ignoreCase = true) }
                    .map { it.name to pfs.openEntry(it) }
            }
            DumpFormat.XCI -> {
                val xci = XciContainer.parse(source)
                ingestTickets(xci.secureEntries.filter { it.name.endsWith(".tik", ignoreCase = true) }, keys) { entry ->
                    xci.openSecureEntry(entry)
                }
                xci.secureEntries
                    .filter { it.name.endsWith(".nca", ignoreCase = true) }
                    .map { it.name to xci.openSecureEntry(it) }
            }
        }
    }

    private fun ingestTickets(
        ticketEntries: List<com.example.core.formats.ContainerEntry>,
        keys: KeySet,
        opener: (com.example.core.formats.ContainerEntry) -> SeekableSource
    ) {
        for (tikEntry in ticketEntries) {
            runCatching {
                val tik = TicketParser.parse(opener(tikEntry))
                if (!tik.isPersonalized) {
                    keys.putEncryptedTitleKey(tik.rightsIdHex, tik.titleKeyBytes, tik.keyGeneration)
                }
            }
        }
    }
}
