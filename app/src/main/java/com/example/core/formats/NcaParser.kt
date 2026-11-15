package com.example.core.formats

import com.example.core.crypto.AesCtrSeekableSource
import com.example.core.crypto.KeySet
import com.example.core.crypto.SwitchCrypto
import com.example.core.io.BinaryUtils
import com.example.core.io.SeekableSource
import java.io.IOException

enum class NcaContentType(val code: Int) {
    PROGRAM(0),
    META(1),
    CONTROL(2),
    MANUAL(3),
    DATA(4),
    PUBLIC_DATA(5),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): NcaContentType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class NcaFsType(val code: Int) {
    ROMFS(0),
    PFS0(1),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): NcaFsType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class NcaEncryptionType(val code: Int) {
    AUTO(0),
    NONE(1),
    AES_XTS(2),
    AES_CTR(3),
    AES_CTR_EX(4),
    AES_CTR_SKIP_LAYER_HASH(5),
    AES_CTR_EX_SKIP_LAYER_HASH(6),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): NcaEncryptionType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class NcaSectionInfo(
    val index: Int,
    val startOffset: Long,
    val endOffset: Long,
    val fsType: NcaFsType,
    val encryptionType: NcaEncryptionType,
    val sectionCtrNonce: ByteArray,
    val dataSubOffset: Long,
    val dataSubSize: Long,
    val bktrRelocationOffset: Long,
    val bktrRelocationSize: Long,
    val bktrSubsectionOffset: Long,
    val bktrSubsectionSize: Long,
    val hasBktr: Boolean
) {
    val size: Long get() = (endOffset - startOffset).coerceAtLeast(0L)
}

class NcaFile private constructor(
    private val source: SeekableSource,
    val magic: String,
    val contentType: NcaContentType,
    val titleId: Long,
    val contentSize: Long,
    val sdkVersion: Long,
    val keyGeneration: Int,
    val rightsIdBytes: ByteArray,
    val keyArea: Array<ByteArray>,
    val sections: List<NcaSectionInfo>
) {
    val titleIdHex: String get() = BinaryUtils.formatTitleId(titleId)
    val baseTitleIdHex: String get() = BinaryUtils.formatTitleId(BinaryUtils.baseTitleIdFromAny(titleId))
    val usesRightsId: Boolean get() = rightsIdBytes.any { it != 0.toByte() }
    val rightsIdHex: String get() = BinaryUtils.bytesToHex(rightsIdBytes)

    fun resolveSectionKey(keys: KeySet): ByteArray {
        if (usesRightsId) {
            val encOrPlainTitleKey = keys.getTitleKey(rightsIdHex)
                ?: throw IOException("Missing titlekey for RightsId $rightsIdHex. Ensure NSP ticket or title.keys is present.")
            val titleKek = keys.getTitleKek(keyGeneration)
            return if (titleKek != null) {
                SwitchCrypto.aes128EcbDecrypt(titleKek, encOrPlainTitleKey)
            } else {
                encOrPlainTitleKey
            }
        }
        val keyAreaKey = keys.getKeyAreaKeyApp(keyGeneration)
            ?: throw IOException("Missing key_area_key_application_%02x in prod.keys".format(keyGeneration))
        return SwitchCrypto.aes128EcbDecrypt(keyAreaKey, keyArea[2])
    }

    fun openSectionRaw(section: NcaSectionInfo, keys: KeySet): SeekableSource {
        val rawSlice = source.slice(section.startOffset, section.size)
        return when (section.encryptionType) {
            NcaEncryptionType.NONE -> rawSlice
            NcaEncryptionType.AES_CTR,
            NcaEncryptionType.AES_CTR_SKIP_LAYER_HASH -> {
                val sectionKey = resolveSectionKey(keys)
                AesCtrSeekableSource(
                    underlying = rawSlice,
                    key = sectionKey,
                    sectionCtrNonce = section.sectionCtrNonce,
                    ctrBaseOffsetInNca = section.startOffset
                )
            }
            NcaEncryptionType.AES_CTR_EX,
            NcaEncryptionType.AES_CTR_EX_SKIP_LAYER_HASH -> {
                val sectionKey = resolveSectionKey(keys)
                AesCtrSeekableSource(
                    underlying = rawSlice,
                    key = sectionKey,
                    sectionCtrNonce = section.sectionCtrNonce,
                    ctrBaseOffsetInNca = section.startOffset
                )
            }
            else -> throw IOException("Unsupported NCA section encryption: ${section.encryptionType}")
        }
    }

    fun openSectionFilesystemSlice(
        section: NcaSectionInfo,
        keys: KeySet,
        baseRomFsForBktr: SeekableSource? = null
    ): SeekableSource {
        if (section.hasBktr &&
            (section.encryptionType == NcaEncryptionType.AES_CTR_EX ||
                section.encryptionType == NcaEncryptionType.AES_CTR_EX_SKIP_LAYER_HASH)
        ) {
            val baseFs = baseRomFsForBktr
                ?: throw IOException("BKTR update section requires a base game RomFS section.")
            val sectionKey = resolveSectionKey(keys)
            val rawSlice = source.slice(section.startOffset, section.size)
            return BktrSeekableSource.create(
                patchSectionRaw = rawSlice,
                sectionStartInNca = section.startOffset,
                sectionKey = sectionKey,
                sectionCtrNonce = section.sectionCtrNonce,
                baseRomFs = baseFs,
                relocationOffset = section.bktrRelocationOffset,
                relocationSize = section.bktrRelocationSize,
                subsectionOffset = section.bktrSubsectionOffset,
                subsectionSize = section.bktrSubsectionSize,
                patchRomFsDataOffset = section.dataSubOffset
            )
        }

        val decrypted = openSectionRaw(section, keys)
        return if (section.dataSubOffset > 0 && section.dataSubSize > 0 &&
            section.dataSubOffset + section.dataSubSize <= decrypted.length
        ) {
            decrypted.slice(section.dataSubOffset, section.dataSubSize)
        } else {
            decrypted
        }
    }

    companion object {
        private const val HEADER_SIZE = 0xC00

        fun parse(source: SeekableSource, keys: KeySet): NcaFile {
            val headerKey = keys.headerKey
                ?: throw IOException("prod.keys is missing header_key")
            if (source.length < HEADER_SIZE) {
                throw IOException("File too small to be NCA (${source.length} bytes)")
            }
            val encHeader = source.readFully(0, HEADER_SIZE)
            val dec = SwitchCrypto.decryptNcaHeaderXts(headerKey, encHeader)

            val magic = String(dec, 0x200, 4, Charsets.US_ASCII)
            if (magic == "NCA0") {
                throw IOException("NCA0 format is not supported.")
            }
            if (magic != "NCA3" && magic != "NCA2") {
                throw IOException("Invalid NCA magic '$magic'. Verify prod.keys header_key.")
            }

            val contentType = NcaContentType.fromCode(dec[0x205].toInt() and 0xFF)
            val cryptoType1 = dec[0x206].toInt() and 0xFF
            val contentSize = BinaryUtils.readU64LE(dec, 0x208)
            val titleId = BinaryUtils.readU64LE(dec, 0x210)
            val sdkVersion = BinaryUtils.readU32LE(dec, 0x21C)
            val cryptoType2 = dec[0x220].toInt() and 0xFF
            val rawCrypto = maxOf(cryptoType1, cryptoType2)
            val keyGeneration = if (rawCrypto > 0) rawCrypto - 1 else 0

            val rightsId = dec.copyOfRange(0x230, 0x240)
            val keyArea = Array(4) { i ->
                val start = 0x300 + i * 16
                dec.copyOfRange(start, start + 16)
            }

            val sections = mutableListOf<NcaSectionInfo>()
            for (i in 0 until 4) {
                val entryOff = 0x240 + i * 16
                val mediaStart = BinaryUtils.readU32LE(dec, entryOff)
                val mediaEnd = BinaryUtils.readU32LE(dec, entryOff + 4)
                if (mediaEnd <= mediaStart) continue

                val startOffset = mediaStart * 0x200L
                val endOffset = minOf(mediaEnd * 0x200L, source.length)
                if (endOffset <= startOffset) continue

                val fsHeaderOff = 0x400 + i * 0x200
                val fsType = NcaFsType.fromCode(dec[fsHeaderOff + 0x03].toInt() and 0xFF)
                val encType = NcaEncryptionType.fromCode(dec[fsHeaderOff + 0x04].toInt() and 0xFF)
                val ctrNonce = dec.copyOfRange(fsHeaderOff + 0x140, fsHeaderOff + 0x148)

                var subOffset = 0L
                var subSize = endOffset - startOffset
                if (fsType == NcaFsType.PFS0) {
                    val pfsOff = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x08 + 0x38)
                    val pfsSz = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x08 + 0x40)
                    if (pfsOff >= 0 && pfsSz > 0 && pfsOff + pfsSz <= endOffset - startOffset) {
                        subOffset = pfsOff
                        subSize = pfsSz
                    }
                } else if (fsType == NcaFsType.ROMFS) {
                    val ivfcMagic = String(dec, fsHeaderOff + 0x08, 4, Charsets.US_ASCII)
                    if (ivfcMagic == "IVFC") {
                        // Level 6 (index 5) of IVFC starts at 0x10 + 5 * 24 = 0x88 inside IVFC header
                        val lvl6Off = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x08 + 0x88)
                        val lvl6Sz = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x08 + 0x90)
                        if (lvl6Off >= 0 && lvl6Sz > 0 && lvl6Off + lvl6Sz <= endOffset - startOffset) {
                            subOffset = lvl6Off
                            subSize = lvl6Sz
                        }
                    }
                }

                // BKTR relocation & subsection headers inside FsHeader (0x100 and 0x120)
                val relocOff = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x100)
                val relocSz = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x108)
                val relocMagic = String(dec, fsHeaderOff + 0x110, 4, Charsets.US_ASCII)
                val subsecOff = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x120)
                val subsecSz = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x128)
                val subsecMagic = String(dec, fsHeaderOff + 0x130, 4, Charsets.US_ASCII)
                val hasBktr = relocMagic == "BKTR" && subsecMagic == "BKTR" && relocSz > 0 && subsecSz > 0

                sections += NcaSectionInfo(
                    index = i,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    fsType = fsType,
                    encryptionType = encType,
                    sectionCtrNonce = ctrNonce,
                    dataSubOffset = subOffset,
                    dataSubSize = subSize,
                    bktrRelocationOffset = relocOff,
                    bktrRelocationSize = relocSz,
                    bktrSubsectionOffset = subsecOff,
                    bktrSubsectionSize = subsecSz,
                    hasBktr = hasBktr
                )
            }

            return NcaFile(
                source = source,
                magic = magic,
                contentType = contentType,
                titleId = titleId,
                contentSize = contentSize,
                sdkVersion = sdkVersion,
                keyGeneration = keyGeneration,
                rightsIdBytes = rightsId,
                keyArea = keyArea,
                sections = sections
            )
        }
    }
}
