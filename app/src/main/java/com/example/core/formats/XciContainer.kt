package com.example.core.formats

import com.example.core.io.BinaryUtils
import com.example.core.io.SeekableSource
import java.io.IOException

class XciContainer private constructor(
    val rootHfs0: Hfs0Container,
    val securePartition: Hfs0Container,
    val rootEntries: List<ContainerEntry>,
    val secureEntries: List<ContainerEntry>
) {
    fun openSecureEntry(entry: ContainerEntry): SeekableSource =
        securePartition.openEntry(entry)

    companion object {
        private const val HEAD_MAGIC = "HEAD"

        fun parse(source: SeekableSource): XciContainer {
            if (source.length < 0x200) throw IOException("File too small to be XCI")
            val header = source.readFully(0, 0x200)
            val magic = String(header, 0x100, 4, Charsets.US_ASCII)
            if (magic != HEAD_MAGIC) {
                throw IOException("Invalid XCI HEAD magic at 0x100: $magic")
            }
            val hfs0Offset = BinaryUtils.readU64LE(header, 0x130)
            val hfs0HeaderSize = BinaryUtils.readU64LE(header, 0x138)
            if (hfs0Offset <= 0 || hfs0Offset >= source.length || hfs0HeaderSize <= 0) {
                throw IOException("Invalid XCI HFS0 offset/size: $hfs0Offset / $hfs0HeaderSize")
            }

            val rootSlice = source.slice(hfs0Offset, source.length - hfs0Offset)
            val rootHfs0 = Hfs0Container.parse(rootSlice)
            val secureEntry = rootHfs0.findByName("secure")
                ?: throw IOException("XCI missing 'secure' HFS0 partition")

            val secureSlice = rootHfs0.openEntry(secureEntry)
            val secureHfs0 = Hfs0Container.parse(secureSlice)
            return XciContainer(
                rootHfs0 = rootHfs0,
                securePartition = secureHfs0,
                rootEntries = rootHfs0.entries,
                secureEntries = secureHfs0.entries
            )
        }
    }
}
