package net.wastu.cleancopy

import java.io.RandomAccessFile

/** Clears MP4 metadata boxes without touching encoded audio/video samples. */
object Mp4MetadataScrubber {
    private val containers = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex",
        "moof", "traf", "mfra", "schi"
    )

    fun scrub(file: java.io.File) {
        RandomAccessFile(file, "rw").use { randomAccessFile ->
            scrubRange(randomAccessFile, 0L, randomAccessFile.length())
        }
    }

    private fun scrubRange(file: RandomAccessFile, start: Long, end: Long) {
        var position = start
        while (position + 8 <= end) {
            file.seek(position)
            val size32 = file.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
            var headerSize = 8L
            val boxSize = when (size32) {
                0L -> end - position
                1L -> {
                    headerSize = 16L
                    file.readLong()
                }
                else -> size32
            }
            if (boxSize < headerSize || position + boxSize > end) return
            val boxEnd = position + boxSize

            when {
                type == "udta" -> scrubMetadataChildren(file, position + headerSize, boxEnd)
                type == "meta" -> scrubMetadataChildren(file, position + headerSize + 4L, boxEnd)
                type == "mvhd" || type == "tkhd" || type == "mdhd" -> {
                    clearTimes(file, position + headerSize)
                }
                type in containers -> scrubRange(file, position + headerSize, boxEnd)
            }
            position = boxEnd
        }
    }

    private fun scrubMetadataChildren(file: RandomAccessFile, start: Long, end: Long) {
        var position = start
        var foundChild = false
        while (position + 8 <= end) {
            val box = readBox(file, position, end) ?: return
            foundChild = true
            when (box.type) {
                "hdlr", "keys" -> Unit
                "data" -> zeroRange(file, box.payloadStart + 8L, box.end)
                else -> {
                    if (hasBox(file, box.payloadStart, box.end)) {
                        scrubMetadataChildren(file, box.payloadStart, box.end)
                    } else {
                        zeroRange(file, box.payloadStart, box.end)
                    }
                }
            }
            position = box.end
        }
        if (!foundChild) return
    }

    private fun hasBox(file: RandomAccessFile, start: Long, end: Long): Boolean =
        readBox(file, start, end) != null

    private fun readBox(file: RandomAccessFile, position: Long, end: Long): Box? {
        if (position + 8 > end) return null
        file.seek(position)
        val size32 = file.readInt().toLong() and 0xffffffffL
        val type = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
        var headerSize = 8L
        val boxSize = when (size32) {
            0L -> end - position
            1L -> {
                headerSize = 16L
                file.readLong()
            }
            else -> size32
        }
        if (boxSize < headerSize || position + boxSize > end) return null
        return Box(type, position + headerSize, position + boxSize)
    }

    private fun clearTimes(file: RandomAccessFile, payloadStart: Long) {
        file.seek(payloadStart)
        val version = file.readUnsignedByte()
        val timeSize = if (version == 1) 8L else 4L
        zeroRange(file, payloadStart + 4, payloadStart + 4 + timeSize)
        zeroRange(file, payloadStart + 4 + timeSize, payloadStart + 4 + timeSize * 2)
    }

    private fun zeroRange(file: RandomAccessFile, start: Long, end: Long) {
        val zeros = ByteArray(4096)
        var position = start
        while (position < end) {
            val count = minOf(zeros.size.toLong(), end - position).toInt()
            file.seek(position)
            file.write(zeros, 0, count)
            position += count
        }
    }

    private data class Box(
        val type: String,
        val payloadStart: Long,
        val end: Long
    )

}
