package com.example.core.formats

import com.example.core.crypto.AesCtrSeekableSource
import com.example.core.crypto.SwitchCrypto
import com.example.core.io.BinaryUtils
import com.example.core.io.SeekableSource
import java.io.IOException

private data class BktrRelocationEntry(
    val virtualOffset: Long,
    val physicalOffset: Long,
    val isPatch: Boolean
)

private data class BktrSubsectionEntry(
    val offset: Long,
    val ctrValue: Int
)

/**
 * Implements Nintendo Switch BKTR (Bucket Tree) relocation + AES-CTR-Ex patching
 * so an Update NCA's RomFS layers seamlessly on top of a Base NCA's RomFS.
 */
class BktrSeekableSource private constructor(
    private val patchSectionRaw: SeekableSource,
    private val sectionStartInNca: Long,
    private val sectionKey: ByteArray,
    private val baseCtrNonce: ByteArray,
    private val baseRomFs: SeekableSource,
    private val relocationEntries: List<BktrRelocationEntry>,
    private val subsectionEntries: List<BktrSubsectionEntry>,
    private val virtualEndOffset: Long,
    private val patchRomFsDataOffset: Long
) : SeekableSource {

    override val length: Long get() = maxOf(virtualEndOffset, baseRomFs.length)

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, readLength: Int): Int {
        if (offset < 0 || offset >= length || readLength <= 0) return 0
        var remaining = minOf(readLength.toLong(), length - offset).toInt()
        var currentVirtual = offset
        var outPos = bufferOffset

        while (remaining > 0) {
            val relocIdx = findRelocationIndex(currentVirtual)
            val entry = relocationEntries[relocIdx]
            val nextBoundary = if (relocIdx + 1 < relocationEntries.size) {
                relocationEntries[relocIdx + 1].virtualOffset
            } else {
                length
            }
            val deltaInBucket = currentVirtual - entry.virtualOffset
            val availableInBucket = (nextBoundary - currentVirtual).coerceAtLeast(1L)
            val chunk = minOf(remaining.toLong(), availableInBucket).toInt()

            if (!entry.isPatch) {
                val basePos = entry.physicalOffset + deltaInBucket
                val read = baseRomFs.readAt(basePos, buffer, outPos, chunk)
                if (read <= 0) break
                currentVirtual += read
                outPos += read
                remaining -= read
            } else {
                val patchSectionPos = patchRomFsDataOffset + entry.physicalOffset + deltaInBucket
                val subIdx = findSubsectionIndex(patchSectionPos)
                val subEntry = subsectionEntries[subIdx]
                val nextSubBoundary = if (subIdx + 1 < subsectionEntries.size) {
                    subsectionEntries[subIdx + 1].offset
                } else {
                    patchSectionRaw.length
                }
                val subChunk = minOf(chunk.toLong(), (nextSubBoundary - patchSectionPos).coerceAtLeast(1L)).toInt()
                val modifiedNonce = buildSubsectionNonce(baseCtrNonce, subEntry.ctrValue)
                val ctrReader = AesCtrSeekableSource(
                    underlying = patchSectionRaw,
                    key = sectionKey,
                    sectionCtrNonce = modifiedNonce,
                    ctrBaseOffsetInNca = sectionStartInNca
                )
                val read = ctrReader.readAt(patchSectionPos, buffer, outPos, subChunk)
                if (read <= 0) break
                currentVirtual += read
                outPos += read
                remaining -= read
            }
        }
        return outPos - bufferOffset
    }

    private fun findRelocationIndex(virtualOffset: Long): Int {
        var low = 0
        var high = relocationEntries.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val v = relocationEntries[mid].virtualOffset
            if (v <= virtualOffset) {
                if (mid == relocationEntries.lastIndex || relocationEntries[mid + 1].virtualOffset > virtualOffset) {
                    return mid
                }
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return 0
    }

    private fun findSubsectionIndex(sectionOffset: Long): Int {
        var low = 0
        var high = subsectionEntries.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val o = subsectionEntries[mid].offset
            if (o <= sectionOffset) {
                if (mid == subsectionEntries.lastIndex || subsectionEntries[mid + 1].offset > sectionOffset) {
                    return mid
                }
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return 0
    }

    private fun buildSubsectionNonce(baseNonce: ByteArray, ctrValue: Int): ByteArray {
        val out = baseNonce.copyOf()
        // Lower 32 bits of the 8-byte nonce carry the subsection CTR generation/counter
        out[0] = (ctrValue and 0xFF).toByte()
        out[1] = ((ctrValue ushr 8) and 0xFF).toByte()
        out[2] = ((ctrValue ushr 16) and 0xFF).toByte()
        out[3] = ((ctrValue ushr 24) and 0xFF).toByte()
        return out
    }

    override fun close() = Unit

    companion object {
        private const val BUCKET_SIZE = 0x4000

        fun create(
            patchSectionRaw: SeekableSource,
            sectionStartInNca: Long,
            sectionKey: ByteArray,
            sectionCtrNonce: ByteArray,
            baseRomFs: SeekableSource,
            relocationOffset: Long,
            relocationSize: Long,
            subsectionOffset: Long,
            subsectionSize: Long,
            patchRomFsDataOffset: Long
        ): BktrSeekableSource {
            val baseCtrReader = AesCtrSeekableSource(
                underlying = patchSectionRaw,
                key = sectionKey,
                sectionCtrNonce = sectionCtrNonce,
                ctrBaseOffsetInNca = sectionStartInNca
            )

            val subBytes = baseCtrReader.readFully(subsectionOffset, subsectionSize.toInt())
            val subsections = parseSubsections(subBytes)
            val relocBytes = baseCtrReader.readFully(relocationOffset, relocationSize.toInt())
            val (relocations, totalVirtualSize) = parseRelocations(relocBytes)

            return BktrSeekableSource(
                patchSectionRaw = patchSectionRaw,
                sectionStartInNca = sectionStartInNca,
                sectionKey = sectionKey,
                baseCtrNonce = sectionCtrNonce,
                baseRomFs = baseRomFs,
                relocationEntries = relocations,
                subsectionEntries = subsections,
                virtualEndOffset = totalVirtualSize,
                patchRomFsDataOffset = patchRomFsDataOffset
            )
        }

        private fun parseSubsections(bytes: ByteArray): List<BktrSubsectionEntry> {
            val bucketCount = BinaryUtils.readU32LE(bytes, 4).toInt()
            val list = mutableListOf<BktrSubsectionEntry>()
            for (b in 0 until bucketCount) {
                val bucketOff = 0x10 + b * BUCKET_SIZE
                if (bucketOff + 16 > bytes.size) break
                val entryCount = BinaryUtils.readU32LE(bytes, bucketOff + 4).toInt()
                for (e in 0 until entryCount) {
                    val off = bucketOff + 0x10 + e * 16
                    if (off + 16 > bytes.size) break
                    val entryOffset = BinaryUtils.readU64LE(bytes, off)
                    val ctr = BinaryUtils.readU32LE(bytes, off + 12).toInt()
                    list += BktrSubsectionEntry(entryOffset, ctr)
                }
            }
            if (list.isEmpty()) list += BktrSubsectionEntry(0L, 0)
            return list
        }

        private fun parseRelocations(bytes: ByteArray): Pair<List<BktrRelocationEntry>, Long> {
            val bucketCount = BinaryUtils.readU32LE(bytes, 4).toInt()
            val totalSize = BinaryUtils.readU64LE(bytes, 8)
            val list = mutableListOf<BktrRelocationEntry>()
            for (b in 0 until bucketCount) {
                val bucketOff = 0x10 + b * BUCKET_SIZE
                if (bucketOff + 16 > bytes.size) break
                val entryCount = BinaryUtils.readU32LE(bytes, bucketOff + 4).toInt()
                for (e in 0 until entryCount) {
                    val off = bucketOff + 0x10 + e * 20
                    if (off + 20 > bytes.size) break
                    val virtOff = BinaryUtils.readU64LE(bytes, off)
                    val physOff = BinaryUtils.readU64LE(bytes, off + 8)
                    val isPatch = BinaryUtils.readU32LE(bytes, off + 16) != 0L
                    list += BktrRelocationEntry(virtOff, physOff, isPatch)
                }
            }
            if (list.isEmpty()) {
                list += BktrRelocationEntry(0L, 0L, isPatch = false)
            }
            return list to totalSize
        }
    }
}
