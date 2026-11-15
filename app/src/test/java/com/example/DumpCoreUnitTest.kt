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
            target = ExtractionTarget.BOTH,
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
        romFsSection: ByteArray?
    ): ByteArray {
        val header = ByteArray(0xC00)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(0x200)
        buf.put("NCA3".toByteArray(Charsets.US_ASCII))
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
            header[fsHeaderOff + 0x03] = fsType.code.toByte()
            header[fsHeaderOff + 0x04] = NcaEncryptionType.AES_CTR.code.toByte()
            val ctrNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, (idx + 1).toByte())
            System.arraycopy(ctrNonce, 0, header, fsHeaderOff + 0x140, 8)

            // Encrypt section data with AES-CTR matching SwitchCrypto
            encryptedSections += encryptCtrSection(plainBytes, plainSectionKey, ctrNonce, curOffset)
            curOffset += plainBytes.size
        }

        val encHeader = SwitchCrypto.encryptNcaHeaderXts(headerKey, header)
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
