package com.example.core.formats

import com.example.core.crypto.AesCtrSeekableSource
import com.example.core.crypto.AesXtsSeekableSource
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
    val hashType: Int,
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
    val keyIndex: Int,
    val rightsIdBytes: ByteArray,
    val keyArea: Array<ByteArray>,
    val sections: List<NcaSectionInfo>
) {
    val titleIdHex: String get() = BinaryUtils.formatTitleId(titleId)
    val baseTitleIdHex: String get() = BinaryUtils.formatTitleId(BinaryUtils.baseTitleIdFromAny(titleId))
    val usesRightsId: Boolean get() = rightsIdBytes.any { it != 0.toByte() }
    val rightsIdHex: String get() = BinaryUtils.bytesToHex(rightsIdBytes)

    @Volatile
    private var verifiedSectionKey: ByteArray? = null

    fun resolveSectionKey(keys: KeySet): ByteArray {
        verifiedSectionKey?.let { return it }
        return resolveSectionKeyCandidates(keys).first().second
    }

    private fun resolveSectionKeyCandidates(keys: KeySet): List<Pair<String, ByteArray>> {
        val candidates = mutableListOf<Pair<String, ByteArray>>()
        val seen = HashSet<String>()
        fun addCandidate(label: String, key: ByteArray?) {
            if (key == null || key.size != 16) return
            val hex = BinaryUtils.bytesToHex(key)
            if (seen.add(hex)) {
                candidates += label to key
            }
        }

        if (usesRightsId) {
            keys.getPlainTitleKey(rightsIdHex)?.let { addCandidate("title.keys", it) }

            val tikEntry = keys.getEncryptedTitleKey(rightsIdHex)
            if (tikEntry != null) {
                val rawTikRev = tikEntry.masterKeyRevision
                val normTikGen = if (rawTikRev > 0) rawTikRev - 1 else 0
                val rightsByte = rightsIdBytes[15].toInt() and 0xFF
                val normRightsGen = if (rightsByte > 0) rightsByte - 1 else 0

                val genPriority = listOf(
                    normTikGen to "tik_norm($normTikGen)",
                    keyGeneration to "nca_gen($keyGeneration)",
                    rawTikRev to "tik_raw($rawTikRev)",
                    normRightsGen to "rights_norm($normRightsGen)",
                    rightsByte to "rights_raw($rightsByte)"
                ).distinctBy { it.first }

                for ((gen, tag) in genPriority) {
                    keys.getTitleKek(gen)?.let { kek ->
                        addCandidate("tik+$tag", SwitchCrypto.aes128EcbDecrypt(kek, tikEntry.encryptedKey))
                    }
                }
                // Try any remaining titlekeks in prod.keys in case NSP repack mismatched ticket/NCA revisions
                for (gen in keys.allKnownTitleKekGenerations()) {
                    keys.getTitleKek(gen)?.let { kek ->
                        addCandidate("tik+fallback($gen)", SwitchCrypto.aes128EcbDecrypt(kek, tikEntry.encryptedKey))
                    }
                }
                // Fallback for fake/decrypted-titlekey tickets
                addCandidate("tik_raw_plain", tikEntry.encryptedKey)
            }

            // Fallback in case a tool stripped RightsId encryption into keyArea[2] without zeroing rightsId
            keys.getKeyAreaKey(keyIndex, keyGeneration)?.let { kaek ->
                addCandidate("kaek_fallback", SwitchCrypto.aes128EcbDecrypt(kaek, keyArea[2]))
            }

            if (candidates.isEmpty()) {
                if (tikEntry == null) {
                    throw IOException("Missing titlekey for RightsId $rightsIdHex. Ensure NSP ticket or title.keys is present.")
                } else {
                    throw IOException("Missing titlekek_%02x in prod.keys to decrypt NSP ticket.".format(keyGeneration))
                }
            }
            return candidates
        }

        val primaryKaek = keys.getKeyAreaKey(keyIndex, keyGeneration)
            ?: throw IOException("Missing key_area_key_application_%02x in prod.keys".format(keyGeneration))
        addCandidate("kaek($keyIndex,$keyGeneration)", SwitchCrypto.aes128EcbDecrypt(primaryKaek, keyArea[2]))
        if (keyIndex != 0) {
            keys.getKeyAreaKey(0, keyGeneration)?.let { appKaek ->
                addCandidate("kaek_app($keyGeneration)", SwitchCrypto.aes128EcbDecrypt(appKaek, keyArea[2]))
            }
        }
        return candidates
    }

    private fun resolveXtsSectionKey(keys: KeySet): ByteArray {
        val kaek = keys.getKeyAreaKey(keyIndex, keyGeneration)
            ?: throw IOException("Missing key_area_key_application_%02x in prod.keys".format(keyGeneration))
        val k0 = SwitchCrypto.aes128EcbDecrypt(kaek, keyArea[0])
        val k1 = SwitchCrypto.aes128EcbDecrypt(kaek, keyArea[1])
        return k0 + k1
    }

    private fun openSectionWithKey(section: NcaSectionInfo, keys: KeySet, ctrKey: ByteArray?): SeekableSource {
        val rawSlice = source.slice(section.startOffset, section.size)
        return when (section.encryptionType) {
            NcaEncryptionType.NONE -> rawSlice
            NcaEncryptionType.AES_XTS -> AesXtsSeekableSource(rawSlice, resolveXtsSectionKey(keys))
            NcaEncryptionType.AES_CTR,
            NcaEncryptionType.AES_CTR_SKIP_LAYER_HASH,
            NcaEncryptionType.AES_CTR_EX,
            NcaEncryptionType.AES_CTR_EX_SKIP_LAYER_HASH -> {
                val key = ctrKey ?: resolveSectionKey(keys)
                AesCtrSeekableSource(
                    underlying = rawSlice,
                    key = key,
                    sectionCtrNonce = section.sectionCtrNonce,
                    ctrBaseOffsetInNca = section.startOffset
                )
            }
            else -> throw IOException("Unsupported NCA section encryption: ${section.encryptionType}")
        }
    }

    fun openSectionRaw(section: NcaSectionInfo, keys: KeySet): SeekableSource {
        return openSectionWithKey(section, keys, null)
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

        val isCtrBased = section.encryptionType == NcaEncryptionType.AES_CTR ||
            section.encryptionType == NcaEncryptionType.AES_CTR_SKIP_LAYER_HASH ||
            section.encryptionType == NcaEncryptionType.AES_CTR_EX ||
            section.encryptionType == NcaEncryptionType.AES_CTR_EX_SKIP_LAYER_HASH

        if (isCtrBased && verifiedSectionKey == null) {
            val candidates = resolveSectionKeyCandidates(keys)
            if (candidates.size > 1 || section.fsType == NcaFsType.PFS0) {
                for ((_, candidateKey) in candidates) {
                    val decrypted = openSectionWithKey(section, keys, candidateKey)
                    val sliced = sliceSubSection(decrypted, section)
                    if (section.fsType == NcaFsType.PFS0 && looksLikePfs0(sliced)) {
                        verifiedSectionKey = candidateKey
                        return sliced
                    }
                    if (section.fsType == NcaFsType.PFS0 && section.dataSubOffset > 0 && looksLikePfs0(decrypted)) {
                        verifiedSectionKey = candidateKey
                        return decrypted
                    }
                    if (section.fsType == NcaFsType.ROMFS && looksLikeRomFs(sliced)) {
                        verifiedSectionKey = candidateKey
                        return sliced
                    }
                }
            }
        }

        val decrypted = openSectionRaw(section, keys)
        val sliced = sliceSubSection(decrypted, section)
        if (section.fsType == NcaFsType.PFS0 && !looksLikePfs0(sliced)) {
            if (section.dataSubOffset > 0 && looksLikePfs0(decrypted)) {
                return decrypted
            }
            val head = if (sliced.length >= 4) sliced.readFully(0, 4) else ByteArray(0)
            val hexHead = BinaryUtils.bytesToHex(head)
            val tikRev = keys.getEncryptedTitleKey(rightsIdHex)?.masterKeyRevision
            throw IOException(
                "Invalid PFS0 header (magic=0x$hexHead, nca=$magic, type=${contentType.name}, " +
                    "enc=${section.encryptionType.name}, hash=${section.hashType}, keyGen=$keyGeneration, " +
                    "rights=$usesRightsId, tikRev=${tikRev ?: "none"}, subOff=0x${section.dataSubOffset.toString(16)})"
            )
        }
        return sliced
    }

    private fun sliceSubSection(decrypted: SeekableSource, section: NcaSectionInfo): SeekableSource {
        return if (section.dataSubOffset > 0 && section.dataSubOffset < decrypted.length) {
            val avail = decrypted.length - section.dataSubOffset
            val len = if (section.dataSubSize > 0) minOf(section.dataSubSize, avail) else avail
            decrypted.slice(section.dataSubOffset, len)
        } else {
            decrypted
        }
    }

    private fun looksLikePfs0(src: SeekableSource): Boolean {
        if (src.length < 16) return false
        val hdr = runCatching { src.readFully(0, 4) }.getOrNull() ?: return false
        return hdr[0] == 'P'.code.toByte() &&
            hdr[1] == 'F'.code.toByte() &&
            hdr[2] == 'S'.code.toByte() &&
            hdr[3] == '0'.code.toByte()
    }

    private fun looksLikeRomFs(src: SeekableSource): Boolean {
        if (src.length < 0x50) return false
        val hdr = runCatching { src.readFully(0, 0x50) }.getOrNull() ?: return false
        val headerSize = BinaryUtils.readU64LE(hdr, 0x00)
        if (headerSize < 0x50L || headerSize > 0x200L) return false
        val dirMetaOffset = BinaryUtils.readU64LE(hdr, 0x18)
        val fileMetaOffset = BinaryUtils.readU64LE(hdr, 0x38)
        val dataOffset = BinaryUtils.readU64LE(hdr, 0x48)
        return dirMetaOffset >= headerSize &&
            fileMetaOffset >= dirMetaOffset &&
            dataOffset >= fileMetaOffset &&
            dataOffset <= src.length
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
            val dec = decryptHeaderCompat(headerKey, encHeader)

            val magic = String(dec, 0x200, 4, Charsets.US_ASCII)
            if (magic == "NCA0") {
                throw IOException("NCA0 format is not supported.")
            }
            if (magic != "NCA3" && magic != "NCA2") {
                throw IOException("Invalid NCA magic '$magic'. Verify prod.keys header_key.")
            }

            val contentType = NcaContentType.fromCode(dec[0x205].toInt() and 0xFF)
            val cryptoType1 = dec[0x206].toInt() and 0xFF
            val keyIndex = dec[0x207].toInt() and 0xFF
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
                val sectionSize = endOffset - startOffset

                val fsHeaderOff = 0x400 + i * 0x200
                val fsType = NcaFsType.fromCode(dec[fsHeaderOff + 0x02].toInt() and 0xFF)
                val hashType = dec[fsHeaderOff + 0x03].toInt() and 0xFF
                val encType = NcaEncryptionType.fromCode(dec[fsHeaderOff + 0x04].toInt() and 0xFF)
                val ctrNonce = dec.copyOfRange(fsHeaderOff + 0x140, fsHeaderOff + 0x148)

                var subOffset = 0L
                var subSize = sectionSize
                if (fsType == NcaFsType.PFS0) {
                    val layerCount = if (hashType == 2) {
                        BinaryUtils.readU32LE(dec, fsHeaderOff + 0x08 + 0x24).toInt().coerceIn(1, 5)
                    } else {
                        2
                    }
                    val dataLayerRelOff = 0x28 + (layerCount - 1) * 16
                    val pfsOff = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x08 + dataLayerRelOff)
                    val pfsSz = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x08 + dataLayerRelOff + 8)
                    if (pfsOff >= 0 && pfsOff < sectionSize && pfsSz > 0) {
                        subOffset = pfsOff
                        subSize = minOf(pfsSz, sectionSize - pfsOff)
                    }
                } else if (fsType == NcaFsType.ROMFS) {
                    val ivfcMagic = String(dec, fsHeaderOff + 0x08, 4, Charsets.US_ASCII)
                    if (ivfcMagic == "IVFC") {
                        // Level 6 (index 5) of IVFC starts at 0x10 + 5 * 24 = 0x88 inside IVFC header
                        val lvl6Off = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x08 + 0x88)
                        val lvl6Sz = BinaryUtils.readU64LE(dec, fsHeaderOff + 0x08 + 0x90)
                        if (lvl6Off >= 0 && lvl6Off < sectionSize && lvl6Sz > 0) {
                            subOffset = lvl6Off
                            subSize = minOf(lvl6Sz, sectionSize - lvl6Off)
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
                    hashType = hashType,
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
                keyIndex = keyIndex,
                rightsIdBytes = rightsId,
                keyArea = keyArea,
                sections = sections
            )
        }

        private fun decryptHeaderCompat(headerKey: ByteArray, encHeader: ByteArray): ByteArray {
            // Support already-decrypted NCA headers
            val rawMagic = String(encHeader, 0x200, 4, Charsets.US_ASCII)
            if ((rawMagic == "NCA3" || rawMagic == "NCA2") &&
                (0x340 until 0x400).all { encHeader[it] == 0.toByte() }
            ) {
                return encHeader.copyOf()
            }

            // Decrypt primary 0x400-byte header with sectors 0..1
            val mainDec = SwitchCrypto.decryptNcaHeaderXts(
                headerKey = headerKey,
                encryptedHeader = encHeader.copyOfRange(0, 0x400),
                sectorSize = 0x200,
                startSector = 0L
            )
            val magic = String(mainDec, 0x200, 4, Charsets.US_ASCII)
            val fullDec = ByteArray(HEADER_SIZE)
            System.arraycopy(mainDec, 0, fullDec, 0, 0x400)

            if (magic == "NCA2") {
                // NCA2 encrypts each 0x200-byte FsHeader individually as sector 0 (with fallback to 2 + i)
                for (i in 0 until 4) {
                    val off = 0x400 + i * 0x200
                    val chunk = encHeader.copyOfRange(off, off + 0x200)
                    val sec0 = SwitchCrypto.decryptNcaHeaderXts(headerKey, chunk, 0x200, startSector = 0L)
                    val isSec0ZeroPad = (0x148 until 0x200).all { sec0[it] == 0.toByte() }
                    val chosen = if (isSec0ZeroPad) {
                        sec0
                    } else {
                        val secSeq = SwitchCrypto.decryptNcaHeaderXts(headerKey, chunk, 0x200, startSector = 2L + i)
                        if ((0x148 until 0x200).all { secSeq[it] == 0.toByte() }) secSeq else sec0
                    }
                    System.arraycopy(chosen, 0, fullDec, off, 0x200)
                }
            } else {
                // NCA3 encrypts sectors 2..5 consecutively
                val fsDec = SwitchCrypto.decryptNcaHeaderXts(
                    headerKey = headerKey,
                    encryptedHeader = encHeader.copyOfRange(0x400, HEADER_SIZE),
                    sectorSize = 0x200,
                    startSector = 2L
                )
                System.arraycopy(fsDec, 0, fullDec, 0x400, 0x800)
            }
            return fullDec
        }
    }
}
