package com.example.core.formats

import com.example.core.io.BinaryUtils
import java.io.IOException

enum class ContentMetaType(val code: Int) {
    APPLICATION(0x80),
    PATCH(0x81),
    ADD_ON_CONTENT(0x82),
    DELTA(0x83),
    OTHER(-1);

    companion object {
        fun fromCode(code: Int): ContentMetaType = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

data class CnmtContentRecord(
    val ncaIdHex: String,
    val size: Long,
    val contentType: NcaContentType
)

data class CnmtMetadata(
    val titleId: Long,
    val version: Long,
    val metaType: ContentMetaType,
    val contentRecords: List<CnmtContentRecord>
) {
    val titleIdHex: String get() = BinaryUtils.formatTitleId(titleId)
    val baseTitleIdHex: String get() = BinaryUtils.formatTitleId(BinaryUtils.baseTitleIdFromAny(titleId))
    val formattedVersion: String
        get() {
            val major = (version ushr 16) and 0xFFFF
            return if (major == 0L && version == 0L) "0" else "$version"
        }
}

object CnmtParser {
    fun parse(bytes: ByteArray): CnmtMetadata {
        if (bytes.size < 0x20) throw IOException("CNMT too small: ${bytes.size}")
        val titleId = BinaryUtils.readU64LE(bytes, 0x00)
        val version = BinaryUtils.readU32LE(bytes, 0x08)
        val typeCode = bytes[0x0C].toInt() and 0xFF
        val metaType = ContentMetaType.fromCode(typeCode)
        val extendedHeaderSize = BinaryUtils.readU16LE(bytes, 0x0E)
        val contentCount = BinaryUtils.readU16LE(bytes, 0x10)

        val recordsStart = 0x20 + extendedHeaderSize
        val records = mutableListOf<CnmtContentRecord>()
        for (i in 0 until contentCount) {
            val off = recordsStart + i * 0x38
            if (off + 0x38 > bytes.size) break
            // ContentInfo: 0x00..0x1F hash, 0x20..0x2F ncaId, 0x30..0x35 size (48-bit LE), 0x36 type
            val ncaId = BinaryUtils.bytesToHex(bytes.copyOfRange(off + 0x20, off + 0x30))
            val sizeLow = BinaryUtils.readU32LE(bytes, off + 0x30)
            val sizeHigh = BinaryUtils.readU16LE(bytes, off + 0x34).toLong()
            val size = sizeLow or (sizeHigh shl 32)
            val ncaType = NcaContentType.fromCode(bytes[off + 0x36].toInt() and 0xFF)
            records += CnmtContentRecord(ncaId, size, ncaType)
        }

        return CnmtMetadata(
            titleId = titleId,
            version = version,
            metaType = metaType,
            contentRecords = records
        )
    }
}
