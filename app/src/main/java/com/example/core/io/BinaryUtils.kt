package com.example.core.io

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object BinaryUtils {
    fun readU16LE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun readU32LE(buf: ByteArray, offset: Int): Long {
        return (buf[offset].toLong() and 0xFF) or
            ((buf[offset + 1].toLong() and 0xFF) shl 8) or
            ((buf[offset + 2].toLong() and 0xFF) shl 16) or
            ((buf[offset + 3].toLong() and 0xFF) shl 24)
    }

    fun readU64LE(buf: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }

    fun readU64BE(buf: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    fun writeU64BE(value: Long, out: ByteArray, offset: Int) {
        ByteBuffer.wrap(out, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(value)
    }

    fun readAscii(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = minOf(buf.size, offset + len)
        while (end < limit && buf[end] != 0.toByte()) {
            end++
        }
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    fun readUtf8NullTerminated(buf: ByteArray, offset: Int, maxLen: Int): String {
        var end = offset
        val limit = minOf(buf.size, offset + maxLen)
        while (end < limit && buf[end] != 0.toByte()) {
            end++
        }
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace(" ", "")
        require(clean.length % 2 == 0) { "Invalid hex length: ${clean.length}" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun formatTitleId(titleId: Long): String {
        return String.format(Locale.US, "%016X", titleId)
    }

    fun baseTitleIdFromAny(titleId: Long): Long {
        return titleId and 0xFFFL.inv()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
