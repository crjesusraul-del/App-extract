package com.example.core.formats

import com.example.core.io.BinaryUtils
import com.example.core.io.SeekableSource
import java.io.IOException

data class ParsedTicket(
    val rightsIdHex: String,
    val titleKeyBytes: ByteArray,
    val isPersonalized: Boolean,
    val keyGeneration: Int
)

object TicketParser {
    fun parse(source: SeekableSource): ParsedTicket {
        if (source.length < 0x200) {
            throw IOException("Ticket too small: ${source.length} bytes")
        }
        val toRead = minOf(source.length, 0x400L).toInt()
        val bytes = source.readFully(0, toRead)
        val sigTypeLE = BinaryUtils.readU32LE(bytes, 0)
        // Normalize in case a tool wrote big-endian sigType
        val sigType = when (sigTypeLE) {
            0x00000100L -> 0x010000L
            0x01000100L -> 0x010001L
            0x02000100L -> 0x010002L
            0x03000100L -> 0x010003L
            0x04000100L -> 0x010004L
            0x05000100L -> 0x010005L
            else -> sigTypeLE
        }
        val bodyStart = when (sigType) {
            0x010000L, 0x010003L -> 0x240 // RSA-4096 (0x200 sig + 0x3C pad + 4)
            0x010001L, 0x010004L -> 0x140 // RSA-2048 (0x100 sig + 0x3C pad + 4)
            0x010002L, 0x010005L -> 0x80  // ECDSA-256 (0x3C sig + 0x40 pad + 4)
            else -> 0x140
        }
        if (bytes.size < bodyStart + 0x180) {
            throw IOException("Ticket body truncated (len=${bytes.size}, bodyStart=$bodyStart)")
        }
        val titleKeyBlock = bytes.copyOfRange(bodyStart + 0x40, bodyStart + 0x50)
        val titleKeyType = bytes[bodyStart + 0x141].toInt() and 0xFF
        val masterKeyRev = bytes[bodyStart + 0x145].toInt() and 0xFF
        val rightsId = bytes.copyOfRange(bodyStart + 0x160, bodyStart + 0x170)

        return ParsedTicket(
            rightsIdHex = BinaryUtils.bytesToHex(rightsId),
            titleKeyBytes = titleKeyBlock,
            isPersonalized = titleKeyType != 0,
            keyGeneration = masterKeyRev
        )
    }
}
