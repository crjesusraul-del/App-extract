package com.example.core.formats

import com.example.core.io.BinaryUtils
import com.example.core.io.SeekableSource
import java.io.IOException

class Hfs0Container private constructor(
    private val source: SeekableSource,
    val entries: List<ContainerEntry>
) {
    fun openEntry(entry: ContainerEntry): SeekableSource {
        return source.slice(entry.offset, entry.size)
    }

    fun findByName(name: String): ContainerEntry? =
        entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    companion object {
        private const val MAGIC = "HFS0"
        private const val ENTRY_SIZE = 0x40

        fun parse(source: SeekableSource): Hfs0Container {
            if (source.length < 16) throw IOException("Source too small for HFS0 header")
            val header = source.readFully(0, 16)
            val magic = String(header, 0, 4, Charsets.US_ASCII)
            if (magic != MAGIC) throw IOException("Invalid HFS0 magic: $magic")

            val fileCount = BinaryUtils.readU32LE(header, 4).toInt()
            val stringTableSize = BinaryUtils.readU32LE(header, 8).toInt()
            if (fileCount < 0 || fileCount > 100_000 || stringTableSize < 0 || stringTableSize > 16 * 1024 * 1024) {
                throw IOException("Suspicious HFS0 counts: files=$fileCount, strSize=$stringTableSize")
            }

            val entryTableSize = fileCount * ENTRY_SIZE
            val dataOffset = 16L + entryTableSize + stringTableSize
            val tableBytes = source.readFully(16L, entryTableSize + stringTableSize)

            val entries = ArrayList<ContainerEntry>(fileCount)
            for (i in 0 until fileCount) {
                val base = i * ENTRY_SIZE
                val offset = BinaryUtils.readU64LE(tableBytes, base)
                val size = BinaryUtils.readU64LE(tableBytes, base + 8)
                val strOff = BinaryUtils.readU32LE(tableBytes, base + 16).toInt()
                val name = if (strOff in 0 until stringTableSize) {
                    BinaryUtils.readUtf8NullTerminated(
                        tableBytes,
                        entryTableSize + strOff,
                        stringTableSize - strOff
                    )
                } else {
                    "entry_$i"
                }
                entries += ContainerEntry(
                    name = name,
                    offset = dataOffset + offset,
                    size = size
                )
            }
            return Hfs0Container(source, entries)
        }
    }
}
