package com.example.core

import com.example.core.crypto.KeySet
import com.example.core.crypto.SwitchCrypto
import com.example.core.formats.CnmtMetadata
import com.example.core.formats.CnmtParser
import com.example.core.formats.ContentMetaType
import com.example.core.formats.Hfs0Container
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
import com.example.core.io.SeekableSource
import com.example.data.DumpFormat
import com.example.data.ExtractionTarget
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

interface ExtractionSink {
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

    private const val COPY_BUFFER_SIZE = 256 * 1024

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

        val exeFsSize = programNca?.sections?.firstOrNull { it.fsType == NcaFsType.PFS0 }?.dataSubSize ?: 0L
        val romFsSize = programNca?.sections?.firstOrNull { it.fsType == NcaFsType.ROMFS }?.dataSubSize ?: 0L
        val hasBktr = programNca?.sections?.any { it.hasBktr } == true

        val fallbackTitle = fileName.substringBeforeLast('.').ifBlank { BinaryUtils.formatTitleId(baseTid) }

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
            hasExeFs = exeFsSize > 0L || programNca?.sections?.any { it.fsType == NcaFsType.PFS0 } == true,
            hasRomFs = romFsSize > 0L || programNca?.sections?.any { it.fsType == NcaFsType.ROMFS } == true,
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
        callbacks: ExtractionProgressCallback
    ) {
        callbacks.onPhase("Parsing container & NCA headers...")
        val baseNcas = openNcasWithTickets(baseSource, baseFormat, keys)
        val baseProgram = findProgramNca(baseNcas, keys)
            ?: throw IOException("No Program NCA found inside base dump.")

        val updateProgram: NcaFile? = if (updateSource != null && updateFormat != null) {
            val updateNcas = openNcasWithTickets(updateSource, updateFormat, keys)
            findProgramNca(updateNcas, keys)
        } else {
            null
        }

        val extractExeFs = target == ExtractionTarget.EXEFS || target == ExtractionTarget.BOTH
        val extractRomFs = target == ExtractionTarget.ROMFS || target == ExtractionTarget.BOTH

        // Select ExeFS source (if update provides a full ExeFS PFS0, prefer update's ExeFS!)
        val exeFsNca = if (extractExeFs) {
            val updExe = updateProgram?.takeIf { nca -> nca.sections.any { it.fsType == NcaFsType.PFS0 } }
            updExe ?: baseProgram
        } else null

        val exeFsSection = exeFsNca?.sections?.firstOrNull { it.fsType == NcaFsType.PFS0 }
        val baseRomFsSection = baseProgram.sections.firstOrNull { it.fsType == NcaFsType.ROMFS }
        val updateRomFsSection = updateProgram?.sections?.firstOrNull { it.fsType == NcaFsType.ROMFS }

        // Pre-scan entries to compute accurate total file counts and byte totals
        val exeFsPfs0 = if (extractExeFs && exeFsNca != null && exeFsSection != null) {
            callbacks.onPhase("Indexing ExeFS partition...")
            Pfs0Container.parse(exeFsNca.openSectionFilesystemSlice(exeFsSection, keys))
        } else null

        val romFsReader = if (extractRomFs) {
            callbacks.onPhase(if (updateRomFsSection != null) "Indexing merged BKTR RomFS..." else "Indexing RomFS...")
            val romSource = when {
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
            RomFsReader.parse(romSource)
        } else null

        val totalFiles = (exeFsPfs0?.entries?.size ?: 0) + (romFsReader?.files?.size ?: 0)
        val totalBytes = (exeFsPfs0?.entries?.sumOf { it.size } ?: 0L) + (romFsReader?.totalSizeBytes ?: 0L)
        if (totalFiles == 0) {
            throw IOException("Selected target ($target) contains 0 extractable files.")
        }

        var filesDone = 0
        var bytesDone = 0L

        if (exeFsPfs0 != null) {
            callbacks.onPhase("Extracting ExeFS...")
            for (entry in exeFsPfs0.entries) {
                if (callbacks.isCancelled()) throw InterruptedException("Extraction cancelled")
                val outPath = "exefs/${entry.name}"
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                val entrySource = exeFsPfs0.openEntry(entry)
                sink.writeFile(outPath, entry.size) { os ->
                    copyFromSeekable(entrySource, os, entry.size, callbacks) { delta ->
                        bytesDone += delta
                        callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                    }
                }
                filesDone++
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
            }
        }

        if (romFsReader != null) {
            callbacks.onPhase(if (updateRomFsSection != null) "Extracting Merged RomFS..." else "Extracting RomFS...")
            for (entry in romFsReader.files) {
                if (callbacks.isCancelled()) throw InterruptedException("Extraction cancelled")
                val outPath = "romfs/${entry.relativePath.trimStart('/')}"
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                val entrySource = romFsReader.openFileSource(entry)
                sink.writeFile(outPath, entry.size) { os ->
                    copyFromSeekable(entrySource, os, entry.size, callbacks) { delta ->
                        bytesDone += delta
                        callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
                    }
                }
                filesDone++
                callbacks.onFileProgress(outPath, filesDone, totalFiles, bytesDone, totalBytes)
            }
        }
    }

    private fun copyFromSeekable(
        source: SeekableSource,
        os: OutputStream,
        totalLen: Long,
        callbacks: ExtractionProgressCallback,
        onBytesDelta: (Long) -> Unit
    ) {
        val buf = ByteArray(COPY_BUFFER_SIZE)
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
        keys: KeySet
    ): List<Pair<String, SeekableSource>> {
        return when (format) {
            DumpFormat.NCA -> listOf("content.nca" to source)
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
                    keys.putTitleKey(tik.rightsIdHex, tik.titleKeyBytes)
                }
            }
        }
    }
}
