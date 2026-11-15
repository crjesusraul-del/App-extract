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
        if (source.length < 0x2C0) {
            throw IOException("Ticket too small: ${source.length} bytes")
        }
        val bytes = source.readFully(0, 0x2C0)
        // Standard Switch common ticket signature type is 0x010004 (RSA-2048 SHA-256),
        // signature 0x100 bytes + 0x40 padding -> ticket body starts at 0x140.
        val sigType = BinaryUtils.readU32LE(bytes, 0)
        val bodyStart = when (sigType) {
            0x010004L -> 0x140
            0x010003L -> 0x240
            else -> 0x140
        }
        if (bytes.size < bodyStart + 0x180) {
            throw IOException("Ticket body truncated")
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
