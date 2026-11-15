package com.example.core.crypto

import com.example.core.io.BinaryUtils
import com.example.core.io.SeekableSource
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object SwitchCrypto {

    fun aes128EcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    fun aes128EcbEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * Decrypts Switch NCA header sectors (0x200 bytes per sector) using AES-128-XTS.
     * Switch NCA XTS places the big-endian 64-bit sector number in bytes 8..15 of the tweak.
     */
    fun decryptNcaHeaderXts(
        headerKey: ByteArray,
        encryptedHeader: ByteArray,
        sectorSize: Int = 0x200,
        startSector: Long = 0L
    ): ByteArray {
        require(headerKey.size == 32) { "NCA header_key must be 32 bytes" }
        require(encryptedHeader.size % 16 == 0) { "Encrypted header must be block-aligned" }

        val dataKey = SecretKeySpec(headerKey.copyOfRange(0, 16), "AES")
        val tweakKey = SecretKeySpec(headerKey.copyOfRange(16, 32), "AES")
        val dataCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, dataKey)
        }
        val tweakCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, tweakKey)
        }

        val out = ByteArray(encryptedHeader.size)
        val blockBuf = ByteArray(16)
        val tweakInput = ByteArray(16)
        var sectorIndex = startSector
        var offset = 0

        while (offset < encryptedHeader.size) {
            tweakInput.fill(0)
            BinaryUtils.writeU64BE(sectorIndex, tweakInput, 8)
            val tweak = tweakCipher.doFinal(tweakInput)

            val sectorEnd = minOf(offset + sectorSize, encryptedHeader.size)
            var blockOff = offset
            while (blockOff < sectorEnd) {
                for (i in 0 until 16) {
                    blockBuf[i] = (encryptedHeader[blockOff + i].toInt() xor tweak[i].toInt()).toByte()
                }
                val decrypted = dataCipher.doFinal(blockBuf)
                for (i in 0 until 16) {
                    out[blockOff + i] = (decrypted[i].toInt() xor tweak[i].toInt()).toByte()
                }
                gf128MulX(tweak)
                blockOff += 16
            }
            sectorIndex++
            offset = sectorEnd
        }
        return out
    }

    fun encryptNcaHeaderXts(
        headerKey: ByteArray,
        plainHeader: ByteArray,
        sectorSize: Int = 0x200,
        startSector: Long = 0L
    ): ByteArray {
        require(headerKey.size == 32) { "NCA header_key must be 32 bytes" }
        val dataKey = SecretKeySpec(headerKey.copyOfRange(0, 16), "AES")
        val tweakKey = SecretKeySpec(headerKey.copyOfRange(16, 32), "AES")
        val dataCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, dataKey)
        }
        val tweakCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, tweakKey)
        }

        val out = ByteArray(plainHeader.size)
        val blockBuf = ByteArray(16)
        val tweakInput = ByteArray(16)
        var sectorIndex = startSector
        var offset = 0

        while (offset < plainHeader.size) {
            tweakInput.fill(0)
            BinaryUtils.writeU64BE(sectorIndex, tweakInput, 8)
            val tweak = tweakCipher.doFinal(tweakInput)

            val sectorEnd = minOf(offset + sectorSize, plainHeader.size)
            var blockOff = offset
            while (blockOff < sectorEnd) {
                for (i in 0 until 16) {
                    blockBuf[i] = (plainHeader[blockOff + i].toInt() xor tweak[i].toInt()).toByte()
                }
                val encrypted = dataCipher.doFinal(blockBuf)
                for (i in 0 until 16) {
                    out[blockOff + i] = (encrypted[i].toInt() xor tweak[i].toInt()).toByte()
                }
                gf128MulX(tweak)
                blockOff += 16
            }
            sectorIndex++
            offset = sectorEnd
        }
        return out
    }

    private fun gf128MulX(tweak: ByteArray) {
        var carry = 0
        for (i in 0 until 16) {
            val b = tweak[i].toInt() and 0xFF
            val nextCarry = (b ushr 7) and 1
            tweak[i] = (((b shl 1) or carry) and 0xFF).toByte()
            carry = nextCarry
        }
        if (carry != 0) {
            tweak[0] = (tweak[0].toInt() xor 0x87).toByte()
        }
    }

    fun buildCtrIv(sectionCtrNonce: ByteArray, ncaAbsoluteOffset: Long): ByteArray {
        require(sectionCtrNonce.size == 8) { "CTR nonce must be 8 bytes" }
        val iv = ByteArray(16)
        // Reverse the 8-byte little-endian section CTR into the upper 64 bits of the big-endian IV
        for (i in 0 until 8) {
            iv[i] = sectionCtrNonce[7 - i]
        }
        BinaryUtils.writeU64BE(ncaAbsoluteOffset ushr 4, iv, 8)
        return iv
    }
}

class AesCtrSeekableSource(
    private val underlying: SeekableSource,
    private val key: ByteArray,
    private val sectionCtrNonce: ByteArray,
    private val ctrBaseOffsetInNca: Long
) : SeekableSource {

    override val length: Long get() = underlying.length

    private val keySpec = SecretKeySpec(key, "AES")

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, readLength: Int): Int {
        if (offset < 0 || offset >= length || readLength <= 0) return 0
        val clamped = minOf(readLength.toLong(), length - offset).toInt()

        val absStart = ctrBaseOffsetInNca + offset
        val blockAlign = absStart and 0xFL.inv()
        val intraBlockOffset = (absStart - blockAlign).toInt()

        val underlyingStart = offset - intraBlockOffset
        val paddedLen = ((intraBlockOffset + clamped + 15) / 16) * 16
        val encBuf = ByteArray(paddedLen)

        var totalRead = 0
        while (totalRead < paddedLen && underlyingStart + totalRead < length) {
            val n = underlying.readAt(underlyingStart + totalRead, encBuf, totalRead, paddedLen - totalRead)
            if (n <= 0) break
            totalRead += n
        }
        if (totalRead <= intraBlockOffset) return 0

        val blocksToDecrypt = ((totalRead + 15) / 16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, keySpec)
        }
        val counterBlock = ByteArray(16)
        val keystream = ByteArray(16)

        val actualOutLen = minOf(clamped, totalRead - intraBlockOffset)
        var outPos = 0

        for (b in 0 until blocksToDecrypt) {
            val blockAbsOffset = blockAlign + (b.toLong() * 16L)
            val iv = SwitchCrypto.buildCtrIv(sectionCtrNonce, blockAbsOffset)
            System.arraycopy(iv, 0, counterBlock, 0, 16)
            cipher.doFinal(counterBlock, 0, 16, keystream, 0)

            val encStart = b * 16
            val copyStart = if (b == 0) intraBlockOffset else 0
            val copyEnd = minOf(16, if (b == 0) intraBlockOffset + actualOutLen else (encStart + 16).coerceAtMost(intraBlockOffset + actualOutLen) - encStart)
            for (i in copyStart until copyEnd) {
                if (outPos >= actualOutLen) break
                buffer[bufferOffset + outPos] = (encBuf[encStart + i].toInt() xor keystream[i].toInt()).toByte()
                outPos++
            }
        }
        return outPos
    }

    override fun close() = Unit
}
