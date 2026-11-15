package com.example.core.formats

import com.example.core.io.BinaryUtils
import com.example.core.io.SeekableSource
import java.io.IOException

data class RomFsFileEntry(
    val relativePath: String,
    val dataOffset: Long,
    val size: Long
)

class RomFsReader private constructor(
    private val source: SeekableSource,
    private val dataSectionOffset: Long,
    val files: List<RomFsFileEntry>
) {
    val totalSizeBytes: Long get() = files.sumOf { it.size }

    fun readFileBytes(entry: RomFsFileEntry): ByteArray {
        require(entry.size <= 64L * 1024L * 1024L) { "File too large to load into memory: ${entry.relativePath}" }
        return source.readFully(dataSectionOffset + entry.dataOffset, entry.size.toInt())
    }

    fun openFileSource(entry: RomFsFileEntry): SeekableSource {
        return source.slice(dataSectionOffset + entry.dataOffset, entry.size)
    }

    fun findFileByName(fileName: String): RomFsFileEntry? =
        files.firstOrNull { it.relativePath.substringAfterLast('/').equals(fileName, ignoreCase = true) }

    companion object {
        private const val INVALID_ENTRY = 0xFFFFFFFFL

        fun parse(source: SeekableSource): RomFsReader {
            if (source.length < 0x50) {
                throw IOException("Source too small for RomFS header (${source.length} bytes)")
            }
            val header = source.readFully(0, 0x50)
            val headerSize = BinaryUtils.readU64LE(header, 0x00)
            if (headerSize < 0x50 || headerSize > 0x200) {
                throw IOException("Invalid RomFS header size: $headerSize")
            }

            val dirMetaTableOffset = BinaryUtils.readU64LE(header, 0x18)
            val dirMetaTableSize = BinaryUtils.readU64LE(header, 0x20)
            val fileMetaTableOffset = BinaryUtils.readU64LE(header, 0x38)
            val fileMetaTableSize = BinaryUtils.readU64LE(header, 0x40)
            val dataOffset = BinaryUtils.readU64LE(header, 0x48)

            val dirMeta = source.readFully(dirMetaTableOffset, dirMetaTableSize.toInt())
            val fileMeta = source.readFully(fileMetaTableOffset, fileMetaTableSize.toInt())

            val collected = mutableListOf<RomFsFileEntry>()
            val visitedDirs = HashSet<Int>()

            fun walkDirectory(dirOffset: Int, parentPath: String) {
                if (dirOffset < 0 || dirOffset + 0x18 > dirMeta.size || !visitedDirs.add(dirOffset)) return
                val childDir = BinaryUtils.readU32LE(dirMeta, dirOffset + 0x08)
                val nextSibling = BinaryUtils.readU32LE(dirMeta, dirOffset + 0x04)
                val firstFile = BinaryUtils.readU32LE(dirMeta, dirOffset + 0x0C)
                val nameLen = BinaryUtils.readU32LE(dirMeta, dirOffset + 0x14).toInt()

                val dirName = if (nameLen > 0 && dirOffset + 0x18 + nameLen <= dirMeta.size) {
                    String(dirMeta, dirOffset + 0x18, nameLen, Charsets.UTF_8)
                } else {
                    ""
                }
                val currentPath = when {
                    parentPath.isEmpty() && dirName.isEmpty() -> ""
                    parentPath.isEmpty() -> dirName
                    dirName.isEmpty() -> parentPath
                    else -> "$parentPath/$dirName"
                }

                // Walk files in this directory
                var fileOff = firstFile
                val visitedFiles = HashSet<Int>()
                while (fileOff != INVALID_ENTRY && fileOff < fileMeta.size) {
                    val fIdx = fileOff.toInt()
                    if (!visitedFiles.add(fIdx) || fIdx + 0x20 > fileMeta.size) break
                    val nextFile = BinaryUtils.readU32LE(fileMeta, fIdx + 0x04)
                    val fileDataOff = BinaryUtils.readU64LE(fileMeta, fIdx + 0x08)
                    val fileSize = BinaryUtils.readU64LE(fileMeta, fIdx + 0x10)
                    val fNameLen = BinaryUtils.readU32LE(fileMeta, fIdx + 0x1C).toInt()
                    val fileName = if (fNameLen > 0 && fIdx + 0x20 + fNameLen <= fileMeta.size) {
                        String(fileMeta, fIdx + 0x20, fNameLen, Charsets.UTF_8)
                    } else {
                        "file_$fIdx"
                    }
                    val fullPath = if (currentPath.isEmpty()) fileName else "$currentPath/$fileName"
                    collected += RomFsFileEntry(
                        relativePath = fullPath,
                        dataOffset = fileDataOff,
                        size = fileSize
                    )
                    fileOff = nextFile
                }

                if (childDir != INVALID_ENTRY && childDir < dirMeta.size) {
                    walkDirectory(childDir.toInt(), currentPath)
                }
                if (nextSibling != INVALID_ENTRY && nextSibling < dirMeta.size) {
                    walkDirectory(nextSibling.toInt(), parentPath)
                }
            }

            if (dirMeta.size >= 0x18) {
                walkDirectory(0, "")
            }

            return RomFsReader(source, dataOffset, collected)
        }
    }
}
