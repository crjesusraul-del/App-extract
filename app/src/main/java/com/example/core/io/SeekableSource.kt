package com.example.core.io

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

interface SeekableSource : Closeable {
    val length: Long

    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, readLength: Int): Int

    fun readFully(offset: Long, readLength: Int): ByteArray {
        val out = ByteArray(readLength)
        var total = 0
        while (total < readLength) {
            val count = readAt(offset + total, out, total, readLength - total)
            if (count <= 0) {
                throw IOException("Unexpected EOF at offset ${offset + total}, wanted $readLength bytes, got $total")
            }
            total += count
        }
        return out
    }

    fun slice(offset: Long, sliceLength: Long): SeekableSource {
        require(offset >= 0 && sliceLength >= 0 && offset + sliceLength <= length) {
            "Slice [$offset, ${offset + sliceLength}) out of bounds for length $length"
        }
        return SubSeekableSource(this, offset, sliceLength)
    }
}

class ByteArraySeekableSource(private val data: ByteArray) : SeekableSource {
    override val length: Long get() = data.size.toLong()

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, readLength: Int): Int {
        if (offset >= data.size || readLength <= 0) return 0
        val available = (data.size - offset.toInt()).coerceAtMost(readLength)
        System.arraycopy(data, offset.toInt(), buffer, bufferOffset, available)
        return available
    }

    override fun close() = Unit
}

class SubSeekableSource(
    private val parent: SeekableSource,
    private val baseOffset: Long,
    override val length: Long
) : SeekableSource {
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, readLength: Int): Int {
        if (offset < 0 || offset >= length || readLength <= 0) return 0
        val clamped = minOf(readLength.toLong(), length - offset).toInt()
        return parent.readAt(baseOffset + offset, buffer, bufferOffset, clamped)
    }

    override fun close() {
        // Parent lifecycle managed by owner
    }
}

/**
 * Read-ahead window cache that batches thousands of small sequential RomFS/metadata
 * reads into large underlying I/O + decryption chunks, while bypassing the cache
 * for large streaming reads.
 */
class BufferedSeekableSource(
    private val underlying: SeekableSource,
    private val windowSize: Int = 512 * 1024
) : SeekableSource {
    override val length: Long get() = underlying.length

    private val cache = ByteArray(windowSize)
    private var cacheStart = -1L
    private var cacheLen = 0

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, readLength: Int): Int {
        if (offset < 0 || offset >= length || readLength <= 0) return 0
        val clamped = minOf(readLength.toLong(), length - offset).toInt()
        if (clamped >= windowSize) {
            return underlying.readAt(offset, buffer, bufferOffset, clamped)
        }
        if (cacheStart >= 0 && offset >= cacheStart && offset < cacheStart + cacheLen) {
            val inCacheOff = (offset - cacheStart).toInt()
            val avail = minOf(clamped, cacheLen - inCacheOff)
            System.arraycopy(cache, inCacheOff, buffer, bufferOffset, avail)
            return avail
        }
        val toFill = minOf(windowSize.toLong(), length - offset).toInt()
        var filled = 0
        while (filled < toFill) {
            val n = underlying.readAt(offset + filled, cache, filled, toFill - filled)
            if (n <= 0) break
            filled += n
        }
        if (filled <= 0) return 0
        cacheStart = offset
        cacheLen = filled
        val toCopy = minOf(clamped, filled)
        System.arraycopy(cache, 0, buffer, bufferOffset, toCopy)
        return toCopy
    }

    override fun close() = Unit
}

class SafSeekableSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val channel: FileChannel,
    override val length: Long
) : SeekableSource {

    private val lock = Any()

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, readLength: Int): Int {
        if (offset < 0 || offset >= length || readLength <= 0) return 0
        val clamped = minOf(readLength.toLong(), length - offset).toInt()
        val byteBuffer = ByteBuffer.wrap(buffer, bufferOffset, clamped)
        synchronized(lock) {
            var read = 0
            while (byteBuffer.hasRemaining()) {
                val n = channel.read(byteBuffer, offset + read)
                if (n <= 0) break
                read += n
            }
            return read
        }
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            pfd.close()
        }
    }

    companion object {
        fun open(contentResolver: ContentResolver, uri: Uri): SafSeekableSource {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Unable to open file descriptor for $uri")
            val inputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val channel = inputStream.channel
            val size = channel.size()
            return SafSeekableSource(pfd, channel, size)
        }
    }
}
