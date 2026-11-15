package com.example

import com.example.core.DumpProcessor
import com.example.core.ExtractionProgressCallback
import com.example.core.ExtractionSink
import com.example.core.crypto.KeySet
import com.example.core.crypto.SwitchCrypto
import com.example.core.formats.NcaContentType
import com.example.core.formats.NcaEncryptionType
import com.example.core.formats.NcaFsType
import com.example.core.io.ByteArraySeekableSource
import com.example.data.DumpFormat
import com.example.data.ExtractionTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class DumpCoreUnitTest {

    @Test
    fun `parse prod keys and validate summary`() {
        val prodKeysText = """
            # Sample prod.keys
            header_key = 00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF
            master_key_00 = 11112222333344445555666677778888
            key_area_key_application_00 = AAAABBBBCCCCDDDDEEEEFFFF00001111
            titlekek_00 = 22223333444455556666777788889999
        """.trimIndent()

        val titleKeysText = """
            01000000000100000000000000000000 = 99998888777766665555444433332222
        """.trimIndent()

        val keys = KeySet.parse(
            ByteArrayInputStream(prodKeysText.toByteArray()),
            ByteArrayInputStream(titleKeysText.toByteArray())
        )
        val summary = keys.summarize()
        assertTrue(summary.isUsableForExtraction)
        assertTrue(summary.hasHeaderKey)
        assertEquals(1, summary.masterKeyCount)
        assertEquals(1, summary.keyAreaAppCount)
        assertEquals(1, summary.titleKeysCount)
    }

    @Test
    fun `inspect and extract encrypted NSP with ExeFS and RomFS`() {
        val headerKey = ByteArray(32) { (it + 1).toByte() }
        val keyAreaKey00 = ByteArray(16) { (it + 0x20).toByte() }
        val plainSectionKey = ByteArray(16) { (it + 0x50).toByte() }

        val keys = KeySet(
            headerKey = headerKey,
            keyAreaKeyApplication = mapOf(0 to keyAreaKey00),
            rawKeyCount = 2
        )

        // Build ExeFS PFS0 containing "main" and "main.npdm"
        val mainBytes = "SWITCH_MAIN_ELF_DATA".toByteArray()
        val npdmBytes = "META_NPDM_DATA".toByteArray()
        val exeFsPfs0 = buildPfs0(listOf("main" to mainBytes, "main.npdm" to npdmBytes))

        // Build RomFS containing "data/level1.bin"
        val levelData = "ROMFS_LEVEL_1_PAYLOAD".toByteArray()
        val romFsBytes = buildMinimalRomFs("data", "level1.bin", levelData)

        // Build Program NCA (encrypted header XTS + encrypted sections AES-CTR)
        val programNca = buildSyntheticNca(
            contentType = NcaContentType.PROGRAM,
            titleId = 0x0100ABCD00001000L,
            headerKey = headerKey,
            keyAreaKey00 = keyAreaKey00,
            plainSectionKey = plainSectionKey,
            pfs0Section = exeFsPfs0,
            romFsSection = romFsBytes
        )

        // Build Control NCA with control.nacp
        val nacpBytes = ByteArray(0x4000).apply {
            val title = "Test Adventure".toByteArray()
            val pub = "Studio Test".toByteArray()
            val ver = "1.2.0".toByteArray()
            System.arraycopy(title, 0, this, 0, title.size)
            System.arraycopy(pub, 0, this, 0x200, pub.size)
            System.arraycopy(ver, 0, this, 0x3060, ver.size)
        }
        val controlRomFs = buildMinimalRomFs(null, "control.nacp", nacpBytes)
        val controlNca = buildSyntheticNca(
            contentType = NcaContentType.CONTROL,
            titleId = 0x0100ABCD00001000L,
            headerKey = headerKey,
            keyAreaKey00 = keyAreaKey00,
            plainSectionKey = plainSectionKey,
            pfs0Section = null,
            romFsSection = controlRomFs
        )

        val nspBytes = buildPfs0(
            listOf(
                "0100abcd00001000.nca" to programNca,
                "control.nca" to controlNca
            )
        )
        val source = ByteArraySeekableSource(nspBytes)

        val inspected = DumpProcessor.inspect(source, "TestAdventure.nsp", keys)
        assertEquals("Test Adventure", inspected.titleName)
        assertEquals("Studio Test", inspected.publisher)
        assertEquals("1.2.0", inspected.displayVersion)
        assertEquals("0100ABCD00001000", inspected.titleIdHex)
        assertTrue(inspected.hasExeFs)
        assertTrue(inspected.hasRomFs)

        val extractedFiles = mutableMapOf<String, ByteArray>()
        val sink = object : ExtractionSink {
            override fun writeFile(relativePath: String, totalSize: Long, writer: (OutputStream) -> Unit) {
                val baos = ByteArrayOutputStream()
                writer(baos)
                extractedFiles[relativePath] = baos.toByteArray()
            }
        }

        DumpProcessor.extract(
            baseSource = source,
            baseFormat = DumpFormat.NSP,
            updateSource = null,
            updateFormat = null,
            target = ExtractionTarget.ALL,
            keys = keys,
            sink = sink,
            callbacks = ExtractionProgressCallback(
                onPhase = {},
                onFileProgress = { _, _, _, _, _ -> },
                isCancelled = { false }
            )
        )

        assertArrayEquals(mainBytes, extractedFiles["exefs/main"])
        assertArrayEquals(npdmBytes, extractedFiles["exefs/main.npdm"])
        assertArrayEquals(levelData, extractedFiles["romfs/data/level1.bin"])
        assertArrayEquals(programNca, extractedFiles["nca/0100abcd00001000.nca"])
        assertArrayEquals(controlNca, extractedFiles["nca/control.nca"])
    }

    @Test
    fun `extract ticketed eShop NSP with HierarchicalSha256 offset and mismatched tik revision`() {
        val headerKey = ByteArray(32) { (it + 1).toByte() }
        val titleKek00 = ByteArray(16) { (it + 0x10).toByte() }
        val titleKek03 = ByteArray(16) { (it + 0x33).toByte() }
        val keyAreaKey00 = ByteArray(16) { (it + 0x20).toByte() }
        val plainTitleKey = ByteArray(16) { (it + 0x77).toByte() }
        val rightsId = ByteArray(16) { if (it < 8) (it + 1).toByte() else 0 }

        val keys = KeySet(
            headerKey = headerKey,
            keyAreaKeyApplication = mapOf(0 to keyAreaKey00),
            titleKeks = mapOf(0 to titleKek00, 3 to titleKek03),
            rawKeyCount = 4
        )

        val mainBytes = "BOTW_MAIN_EXE".toByteArray()
        val exeFsPfs0 = buildPfs0(listOf("main" to mainBytes))
        // Prepend 0x2000 hash table before PFS0 inside Section 0
        val hashTableSize = 0x2000
        val hashedPfs0Section = ByteArray(hashTableSize + ((exeFsPfs0.size + 0x1FF) / 0x200) * 0x200).apply {
            for (i in 0 until hashTableSize) this[i] = (i xor 0x5A).toByte()
            System.arraycopy(exeFsPfs0, 0, this, hashTableSize, exeFsPfs0.size)
        }

        val romData = "BOTW_WORLD_BIN".toByteArray()
        val romFsBytes = buildMinimalRomFs("data", "world.bin", romData)

        // Program NCA uses keyGeneration=0, RightsId != 0, and HierarchicalSha256 pfs0_offset=0x2000
        val programNca = buildSyntheticNca(
            contentType = NcaContentType.PROGRAM,
            titleId = 0x01007EF00011E000L,
            headerKey = headerKey,
            keyAreaKey00 = keyAreaKey00,
            plainSectionKey = plainTitleKey,
            pfs0Section = hashedPfs0Section,
            romFsSection = romFsBytes,
            rightsId = rightsId,
            pfs0SubOffset = hashTableSize.toLong(),
            pfs0SubSize = exeFsPfs0.size.toLong(),
            useNca2Header = false
        )

        // Ticket encrypted with titlekek_03 while masterKeyRevision is 4 (-> 3), even though NCA keyGeneration=0
        val encTitleKey = SwitchCrypto.aes128EcbEncrypt(titleKek03, plainTitleKey)
        val ticketBytes = buildTicket(
            sigType = 0x010004L,
            encryptedTitleKey = encTitleKey,
            masterKeyRevision = 4,
            rightsId = rightsId
        )

        val nspBytes = buildPfs0(
            listOf(
                "01007ef00011e000.nca" to programNca,
                "01007ef00011e000.tik" to ticketBytes
            )
        )

        val extracted = mutableMapOf<String, ByteArray>()
        val sink = object : ExtractionSink {
            override fun writeFile(relativePath: String, totalSize: Long, writer: (OutputStream) -> Unit) {
                val baos = ByteArrayOutputStream()
                writer(baos)
                extracted[relativePath] = baos.toByteArray()
            }
        }

        DumpProcessor.extract(
            baseSource = ByteArraySeekableSource(nspBytes),
            baseFormat = DumpFormat.NSP,
            updateSource = null,
            updateFormat = null,
            target = ExtractionTarget.BOTH,
            keys = keys,
            sink = sink,
            callbacks = ExtractionProgressCallback({}, { _, _, _, _, _ -> }, { false })
        )

        assertArrayEquals(mainBytes, extracted["exefs/main"])
        assertArrayEquals(romData, extracted["romfs/data/world.bin"])
    }

    @Test
    fun `extract NCA2 where each FsHeader uses sector 0 XTS tweak`() {
        val headerKey = ByteArray(32) { (it + 5).toByte() }
        val keyAreaKey00 = ByteArray(16) { (it + 0x22).toByte() }
        val plainSectionKey = ByteArray(16) { (it + 0x66).toByte() }
        val keys = KeySet(
            headerKey = headerKey,
            keyAreaKeyApplication = mapOf(0 to keyAreaKey00),
            rawKeyCount = 2
        )

        val npdm = "NCA2_NPDM".toByteArray()
        val exeFsPfs0 = buildPfs0(listOf("main.npdm" to npdm))
        val nca2Bytes = buildSyntheticNca(
            contentType = NcaContentType.PROGRAM,
            titleId = 0x0100112233440000L,
            headerKey = headerKey,
            keyAreaKey00 = keyAreaKey00,
            plainSectionKey = plainSectionKey,
            pfs0Section = exeFsPfs0,
            romFsSection = null,
            useNca2Header = true
        )

        val extracted = mutableMapOf<String, ByteArray>()
        val sink = object : ExtractionSink {
            override fun writeFile(relativePath: String, totalSize: Long, writer: (OutputStream) -> Unit) {
                val baos = ByteArrayOutputStream()
                writer(baos)
                extracted[relativePath] = baos.toByteArray()
            }
        }

        DumpProcessor.extract(
            baseSource = ByteArraySeekableSource(nca2Bytes),
            baseFormat = DumpFormat.NCA,
            updateSource = null,
            updateFormat = null,
            target = ExtractionTarget.EXEFS,
            keys = keys,
            sink = sink,
            callbacks = ExtractionProgressCallback({}, { _, _, _, _, _ -> }, { false })
        )

        assertArrayEquals(npdm, extracted["exefs/main.npdm"])
    }

    @Test
    fun `parse ECDSA and RSA-4096 tickets`() {
        val tk = ByteArray(16) { (it + 0x41).toByte() }
        val rid = ByteArray(16) { (0x90 + it).toByte() }

        val ecdsaTik = buildTicket(sigType = 0x010005L, encryptedTitleKey = tk, masterKeyRevision = 1, rightsId = rid)
        val parsedEcdsa = com.example.core.formats.TicketParser.parse(ByteArraySeekableSource(ecdsaTik))
        assertArrayEquals(tk, parsedEcdsa.titleKeyBytes)
        assertEquals(1, parsedEcdsa.keyGeneration)

        val rsa4kTik = buildTicket(sigType = 0x010003L, encryptedTitleKey = tk, masterKeyRevision = 2, rightsId = rid)
        val parsedRsa4k = com.example.core.formats.TicketParser.parse(ByteArraySeekableSource(rsa4kTik))
        assertArrayEquals(tk, parsedRsa4k.titleKeyBytes)
        assertEquals(2, parsedRsa4k.keyGeneration)
    }

    @Test
    fun `AesCtrSeekableSource and BufferedSeekableSource match ECB CTR across unaligned large reads`() {
        val key = ByteArray(16) { (it * 7 + 3).toByte() }
        val nonce = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        val baseOff = 0x10400L
        val plain = ByteArray(150_003) { ((it * 31) xor (it ushr 7)).toByte() }
        val cipherBytes = encryptCtrSection(plain, key, nonce, baseOff)

        val ctrSrc = com.example.core.crypto.AesCtrSeekableSource(
            underlying = ByteArraySeekableSource(cipherBytes),
            key = key,
            sectionCtrNonce = nonce,
            ctrBaseOffsetInNca = baseOff
        )
        val buffered = com.example.core.io.BufferedSeekableSource(ctrSrc, windowSize = 32 * 1024)

        // Unaligned small read inside first sector
        val slice1 = buffered.readFully(13L, 127)
        assertArrayEquals(plain.copyOfRange(13, 140), slice1)

        // Unaligned cross-window read near EOF
        val slice2 = buffered.readFully(100_005L, 49_998)
        assertArrayEquals(plain.copyOfRange(100_005, 150_003), slice2)
    }

    @Test
    fun `AesCtrSeekableSource sequential update fast path backward seek and EOF match ground truth`() {
        val key = ByteArray(16) { (it * 13 + 5).toByte() }
        val nonce = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xA0.toByte(), 0xB1.toByte(), 0xC2.toByte(), 0xD3.toByte())
        val baseOff = 0x20000L
        val plain = ByteArray(10_007) { ((it * 19) xor (it ushr 5)).toByte() }
        val cipherBytes = encryptCtrSection(plain, key, nonce, baseOff)

        val ctrSrc = com.example.core.crypto.AesCtrSeekableSource(
            underlying = ByteArraySeekableSource(cipherBytes),
            key = key,
            sectionCtrNonce = nonce,
            ctrBaseOffsetInNca = baseOff
        )

        // 1. Consecutive 16-byte-aligned sequential reads (exercises cipher.update() fast-path)
        val chunk1 = ByteArray(2048)
        val chunk2 = ByteArray(2048)
        val chunk3 = ByteArray(4096)
        assertEquals(2048, ctrSrc.readAt(0L, chunk1, 0, 2048))
        assertEquals(2048, ctrSrc.readAt(2048L, chunk2, 0, 2048))
        assertEquals(4096, ctrSrc.readAt(4096L, chunk3, 0, 4096))
        assertArrayEquals(plain.copyOfRange(0, 2048), chunk1)
        assertArrayEquals(plain.copyOfRange(2048, 4096), chunk2)
        assertArrayEquals(plain.copyOfRange(4096, 8192), chunk3)

        // 2. Partial EOF read continuing sequentially from 8192 to 10007
        val tail = ByteArray(2048)
        val tailRead = ctrSrc.readAt(8192L, tail, 0, 2048)
        assertEquals(10_007 - 8192, tailRead)
        assertArrayEquals(plain.copyOfRange(8192, 10_007), tail.copyOf(tailRead))

        // 3. Backward unaligned seek after EOF
        val seekBack = ByteArray(333)
        assertEquals(333, ctrSrc.readAt(777L, seekBack, 0, 333))
        assertArrayEquals(plain.copyOfRange(777, 1110), seekBack)

        // 4. Sequential unaligned read right after backward seek
        val seqUnaligned = ByteArray(512)
        assertEquals(512, ctrSrc.readAt(1110L, seqUnaligned, 0, 512))
        assertArrayEquals(plain.copyOfRange(1110, 1622), seqUnaligned)
    }

    @Test
    fun `parallel extraction extracts large file and small files cleanly`() {
        val headerKey = ByteArray(32) { (it + 9).toByte() }
        val keyAreaKey00 = ByteArray(16) { (it + 0x24).toByte() }
        val plainSectionKey = ByteArray(16) { (it + 0x61).toByte() }
        val keys = KeySet(
            headerKey = headerKey,
            keyAreaKeyApplication = mapOf(0 to keyAreaKey00),
            rawKeyCount = 2
        )

        val smallData = "SMALL_EXEF_DATA".toByteArray()
        val bigData = ByteArray(1_200_000) { ((it * 17) xor (it ushr 9)).toByte() }
        val exeFsPfs0 = buildPfs0(listOf("small.bin" to smallData, "big.bin" to bigData))
        val romData = "ROM_PAR_DATA".toByteArray()
        val romFsBytes = buildMinimalRomFs("sub", "asset.bin", romData)

        val ncaBytes = buildSyntheticNca(
            contentType = NcaContentType.PROGRAM,
            titleId = 0x0100555500001000L,
            headerKey = headerKey,
            keyAreaKey00 = keyAreaKey00,
            plainSectionKey = plainSectionKey,
            pfs0Section = exeFsPfs0,
            romFsSection = romFsBytes
        )
        val nspBytes = buildPfs0(listOf("prog.nca" to ncaBytes))

        val extracted = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val parallelSink = object : ExtractionSink {
            override val supportsParallelWrites: Boolean = true
            override fun writeFile(relativePath: String, totalSize: Long, writer: (OutputStream) -> Unit) {
                val baos = ByteArrayOutputStream()
                writer(baos)
                extracted[relativePath] = baos.toByteArray()
            }
        }

        DumpProcessor.extract(
            baseSource = ByteArraySeekableSource(nspBytes),
            baseFormat = DumpFormat.NSP,
            updateSource = null,
            updateFormat = null,
            target = ExtractionTarget.ALL,
            keys = keys,
            sink = parallelSink,
            callbacks = ExtractionProgressCallback({}, { _, _, _, _, _ -> }, { false })
        )

        assertArrayEquals(smallData, extracted["exefs/small.bin"])
        assertArrayEquals(bigData, extracted["exefs/big.bin"])
        assertArrayEquals(romData, extracted["romfs/sub/asset.bin"])
        assertArrayEquals(ncaBytes, extracted["nca/prog.nca"])
    }

    private fun buildTicket(
        sigType: Long,
        encryptedTitleKey: ByteArray,
        masterKeyRevision: Int,
        rightsId: ByteArray
    ): ByteArray {
        val bodyStart = when (sigType) {
            0x010003L, 0x010000L -> 0x240
            0x010005L, 0x010002L -> 0x80
            else -> 0x140
        }
        val out = ByteArray(bodyStart + 0x180)
        ByteBuffer.wrap(out, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sigType.toInt())
        System.arraycopy(encryptedTitleKey, 0, out, bodyStart + 0x40, 16)
        out[bodyStart + 0x141] = 0 // Common ticket
        out[bodyStart + 0x145] = masterKeyRevision.toByte()
        System.arraycopy(rightsId, 0, out, bodyStart + 0x160, 16)
        return out
    }

    private fun buildPfs0(files: List<Pair<String, ByteArray>>): ByteArray {
        val strTableBaos = ByteArrayOutputStream()
        val strOffsets = mutableListOf<Int>()
        for ((name, _) in files) {
            strOffsets += strTableBaos.size()
            strTableBaos.write(name.toByteArray(Charsets.UTF_8))
            strTableBaos.write(0)
        }
        while (strTableBaos.size() % 16 != 0) strTableBaos.write(0)
        val strTable = strTableBaos.toByteArray()

        val headerSize = 16 + files.size * 24 + strTable.size
        val dataBaos = ByteArrayOutputStream()
        val dataOffsets = mutableListOf<Long>()
        for ((_, data) in files) {
            dataOffsets += dataBaos.size().toLong()
            dataBaos.write(data)
        }
        val dataBytes = dataBaos.toByteArray()

        val out = ByteBuffer.allocate(headerSize + dataBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("PFS0".toByteArray(Charsets.US_ASCII))
        out.putInt(files.size)
        out.putInt(strTable.size)
        out.putInt(0)
        for (i in files.indices) {
            out.putLong(dataOffsets[i])
            out.putLong(files[i].second.size.toLong())
            out.putInt(strOffsets[i])
            out.putInt(0)
        }
        out.put(strTable)
        out.put(dataBytes)
        return out.array()
    }

    private fun buildMinimalRomFs(dirName: String?, fileName: String, fileBytes: ByteArray): ByteArray {
        // RomFS header = 0x50 bytes
        val headerSize = 0x50
        val dirMetaBaos = ByteArrayOutputStream()
        val fileMetaBaos = ByteArrayOutputStream()

        fun writeIntLE(os: ByteArrayOutputStream, v: Int) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            os.write(b)
        }

        fun writeLongLE(os: ByteArrayOutputStream, v: Long) {
            val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
            os.write(b)
        }

        if (dirName == null) {
            // Root dir (offset 0) directly holds file (offset 0)
            writeIntLE(dirMetaBaos, 0) // parent
            writeIntLE(dirMetaBaos, -1) // sibling
            writeIntLE(dirMetaBaos, -1) // child dir
            writeIntLE(dirMetaBaos, 0) // first file
            writeIntLE(dirMetaBaos, -1) // hash next
            writeIntLE(dirMetaBaos, 0) // nameLen
        } else {
            val dirNameBytes = dirName.toByteArray(Charsets.UTF_8)
            val paddedDirNameLen = ((dirNameBytes.size + 3) / 4) * 4
            // Root dir (offset 0, size 0x18) -> childDir at 0x18
            writeIntLE(dirMetaBaos, 0)
            writeIntLE(dirMetaBaos, -1)
            writeIntLE(dirMetaBaos, 0x18)
            writeIntLE(dirMetaBaos, -1)
            writeIntLE(dirMetaBaos, -1)
            writeIntLE(dirMetaBaos, 0)

            // Child dir at 0x18 -> first file at 0
            writeIntLE(dirMetaBaos, 0)
            writeIntLE(dirMetaBaos, -1)
            writeIntLE(dirMetaBaos, -1)
            writeIntLE(dirMetaBaos, 0)
            writeIntLE(dirMetaBaos, -1)
            writeIntLE(dirMetaBaos, dirNameBytes.size)
            dirMetaBaos.write(dirNameBytes)
            repeat(paddedDirNameLen - dirNameBytes.size) { dirMetaBaos.write(0) }
        }

        val fNameBytes = fileName.toByteArray(Charsets.UTF_8)
        val paddedFNameLen = ((fNameBytes.size + 3) / 4) * 4
        writeIntLE(fileMetaBaos, if (dirName == null) 0 else 0x18) // parent dir
        writeIntLE(fileMetaBaos, -1) // sibling file
        writeLongLE(fileMetaBaos, 0L) // data offset
        writeLongLE(fileMetaBaos, fileBytes.size.toLong()) // data size
        writeIntLE(fileMetaBaos, -1) // hash next
        writeIntLE(fileMetaBaos, fNameBytes.size)
        fileMetaBaos.write(fNameBytes)
        repeat(paddedFNameLen - fNameBytes.size) { fileMetaBaos.write(0) }

        val dirMeta = dirMetaBaos.toByteArray()
        val fileMeta = fileMetaBaos.toByteArray()

        val dirMetaOff = headerSize.toLong()
        val fileMetaOff = dirMetaOff + dirMeta.size
        val dataOff = ((fileMetaOff + fileMeta.size + 15) / 16) * 16

        val out = ByteBuffer.allocate((dataOff + fileBytes.size).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        out.putLong(headerSize.toLong())
        out.putLong(headerSize.toLong()) // dirHashOffset (unused in walk)
        out.putLong(0L)
        out.putLong(dirMetaOff)
        out.putLong(dirMeta.size.toLong())
        out.putLong(fileMetaOff) // fileHashOffset
        out.putLong(0L)
        out.putLong(fileMetaOff)
        out.putLong(fileMeta.size.toLong())
        out.putLong(dataOff)

        out.position(dirMetaOff.toInt())
        out.put(dirMeta)
        out.position(fileMetaOff.toInt())
        out.put(fileMeta)
        out.position(dataOff.toInt())
        out.put(fileBytes)
        return out.array()
    }

    private fun buildSyntheticNca(
        contentType: NcaContentType,
        titleId: Long,
        headerKey: ByteArray,
        keyAreaKey00: ByteArray,
        plainSectionKey: ByteArray,
        pfs0Section: ByteArray?,
        romFsSection: ByteArray?,
        rightsId: ByteArray? = null,
        pfs0SubOffset: Long = 0L,
        pfs0SubSize: Long = 0L,
        useNca2Header: Boolean = false
    ): ByteArray {
        val header = ByteArray(0xC00)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(0x200)
        buf.put((if (useNca2Header) "NCA2" else "NCA3").toByteArray(Charsets.US_ASCII))
        buf.put(0.toByte()) // distribution
        buf.put(contentType.code.toByte())
        buf.put(0.toByte()) // cryptoType1
        buf.put(0.toByte()) // keyIndex

        val sectionsData = mutableListOf<Triple<Int, NcaFsType, ByteArray>>()
        var curOffset = 0xC00L

        fun pad512(data: ByteArray): ByteArray {
            val paddedSize = ((data.size + 0x1FF) / 0x200) * 0x200
            return data.copyOf(paddedSize)
        }

        if (pfs0Section != null) {
            sectionsData += Triple(0, NcaFsType.PFS0, pad512(pfs0Section))
        }
        if (romFsSection != null) {
            sectionsData += Triple(if (pfs0Section != null) 1 else 0, NcaFsType.ROMFS, pad512(romFsSection))
        }

        val totalSize = 0xC00L + sectionsData.sumOf { it.third.size.toLong() }
        buf.putLong(totalSize)
        buf.putLong(titleId)

        if (rightsId != null && rightsId.size == 16) {
            System.arraycopy(rightsId, 0, header, 0x230, 16)
        }

        // Key area slot 2 = encrypted section key
        val encryptedKey2 = SwitchCrypto.aes128EcbEncrypt(keyAreaKey00, plainSectionKey)
        System.arraycopy(encryptedKey2, 0, header, 0x320, 16)

        val encryptedSections = mutableListOf<ByteArray>()
        for ((idx, fsType, plainBytes) in sectionsData) {
            val startMedia = (curOffset / 0x200L).toInt()
            val endMedia = ((curOffset + plainBytes.size) / 0x200L).toInt()
            val entryOff = 0x240 + idx * 16
            buf.position(entryOff)
            buf.putInt(startMedia)
            buf.putInt(endMedia)
            buf.putInt(1)

            val fsHeaderOff = 0x400 + idx * 0x200
            header[fsHeaderOff + 0x02] = fsType.code.toByte()
            header[fsHeaderOff + 0x03] = if (fsType == NcaFsType.PFS0) 2.toByte() else 3.toByte()
            header[fsHeaderOff + 0x04] = NcaEncryptionType.AES_CTR.code.toByte()
            if (fsType == NcaFsType.PFS0 && pfs0SubSize > 0) {
                buf.position(fsHeaderOff + 0x08 + 0x24)
                buf.putInt(2) // layerCount
                buf.putLong(0L)
                buf.putLong(pfs0SubOffset)
                buf.putLong(pfs0SubOffset)
                buf.putLong(pfs0SubSize)
            }
            val ctrNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, (idx + 1).toByte())
            System.arraycopy(ctrNonce, 0, header, fsHeaderOff + 0x140, 8)

            // Encrypt section data with AES-CTR matching SwitchCrypto
            encryptedSections += encryptCtrSection(plainBytes, plainSectionKey, ctrNonce, curOffset)
            curOffset += plainBytes.size
        }

        val encHeader = if (useNca2Header) {
            val mainEnc = SwitchCrypto.encryptNcaHeaderXts(headerKey, header.copyOfRange(0, 0x400), startSector = 0L)
            val combined = ByteArray(0xC00)
            System.arraycopy(mainEnc, 0, combined, 0, 0x400)
            for (i in 0 until 4) {
                val off = 0x400 + i * 0x200
                val fsEnc = SwitchCrypto.encryptNcaHeaderXts(headerKey, header.copyOfRange(off, off + 0x200), startSector = 0L)
                System.arraycopy(fsEnc, 0, combined, off, 0x200)
            }
            combined
        } else {
            SwitchCrypto.encryptNcaHeaderXts(headerKey, header)
        }
        val fullOut = ByteArrayOutputStream()
        fullOut.write(encHeader)
        for (sec in encryptedSections) {
            fullOut.write(sec)
        }
        return fullOut.toByteArray()
    }

    private fun encryptCtrSection(
        plain: ByteArray,
        key: ByteArray,
        ctrNonce: ByteArray,
        sectionStartInNca: Long
    ): ByteArray {
        val out = ByteArray(plain.size)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        var offset = 0
        while (offset < plain.size) {
            val iv = SwitchCrypto.buildCtrIv(ctrNonce, sectionStartInNca + offset)
            val ks = cipher.doFinal(iv)
            val chunk = minOf(16, plain.size - offset)
            for (i in 0 until chunk) {
                out[offset + i] = (plain[offset + i].toInt() xor ks[i].toInt()).toByte()
            }
            offset += 16
        }
        return out
    }
}
