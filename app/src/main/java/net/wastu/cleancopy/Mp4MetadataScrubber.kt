package net.wastu.cleancopy

import java.io.File
import java.io.RandomAccessFile

/** Clears metadata without changing box offsets or encoded audio/video samples. */
object Mp4MetadataScrubber {
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf", "mfra", "schi")

    fun scrub(file: File) = RandomAccessFile(file, "rw").use { scrubRange(it, 0, it.length(), 0) }

    private fun scrubRange(file: RandomAccessFile, start: Long, end: Long, depth: Int) {
        require(depth < 32) { "The MP4 container is nested too deeply" }
        var position = start
        while (position < end) {
            require(end - position >= 8) { "Truncated MP4 box" }
            file.seek(position)
            val size32 = file.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(file::readFully).toString(Charsets.ISO_8859_1)
            val headerSize = if (size32 == 1L) 16L else 8L
            require(end - position >= headerSize) { "Truncated MP4 box header" }
            val size = when (size32) { 0L -> end - position; 1L -> file.readLong(); else -> size32 }
            require(size >= headerSize && size <= end - position) { "Invalid MP4 box size" }
            val payload = position + headerSize
            val boxEnd = position + size
            when {
                type in setOf("udta", "meta", "uuid") -> {
                    file.seek(position + 4)
                    file.write("free".toByteArray(Charsets.US_ASCII))
                    zero(file, payload, boxEnd)
                }
                type in setOf("mvhd", "tkhd", "mdhd") -> {
                    require(boxEnd - payload >= 4) { "Truncated MP4 timing header" }
                    file.seek(payload)
                    val version = file.readUnsignedByte()
                    require(version in 0..1) { "Unsupported MP4 timing version" }
                    val bytes = if (version == 1) 16L else 8L
                    require(boxEnd - payload >= 4 + bytes) { "Truncated MP4 timestamps" }
                    zero(file, payload + 4, payload + 4 + bytes)
                }
                type in containers -> scrubRange(file, payload, boxEnd, depth + 1)
            }
            position = boxEnd
        }
    }

    private fun zero(file: RandomAccessFile, start: Long, end: Long) {
        file.seek(start)
        val buffer = ByteArray(8192)
        var remaining = end - start
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            file.write(buffer, 0, count)
            remaining -= count
        }
    }
}
