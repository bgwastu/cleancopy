package net.wastu.cleancopy

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Removes metadata containers while copying the encoded pixels and animation unchanged. */
object ImageMetadataScrubber {
    fun scrub(file: File, extension: String): Int {
        val temporary = File(file.parentFile, "${file.name}.scrub")
        try {
            val removed = file.inputStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    when (extension) {
                        "jpg" -> jpeg(DataInputStream(input), DataOutputStream(output))
                        "png" -> png(DataInputStream(input), DataOutputStream(output))
                        "gif" -> gif(DataInputStream(input), DataOutputStream(output))
                        "webp" -> webp(DataInputStream(input), DataOutputStream(output))
                        else -> error("Unsupported image container")
                    }
                }
            }
            if (extension == "webp") java.io.RandomAccessFile(temporary, "rw").use { output ->
                val size = output.length() - 8
                require(size in 4..0xffffffffL) { "Invalid WebP output length" }
                output.seek(4)
                repeat(4) { output.write(((size shr (8 * it)) and 0xff).toInt()) }
            }
            check(temporary.renameTo(file)) { "Could not finish metadata cleanup" }
            return removed
        } finally {
            temporary.delete()
        }
    }

    private fun jpeg(input: DataInputStream, output: DataOutputStream): Int {
        require(input.readUnsignedShort() == 0xffd8) { "Invalid JPEG header" }
        output.writeShort(0xffd8)
        var removed = 0
        var marker: Int? = null
        while (true) {
            val code = marker ?: run {
                require(input.readUnsignedByte() == 0xff) { "Invalid JPEG marker" }
                var next = input.readUnsignedByte()
                while (next == 0xff) next = input.readUnsignedByte()
                next
            }
            marker = null
            if (code == 0xd9) {
                output.writeShort(0xffd9)
                // Motion-photo clips and other appended data can contain separate private metadata.
                if (input.read() != -1) removed++
                return removed
            }
            require(code != 0x00 && code != 0xd8) { "Invalid JPEG marker" }
            if (code == 0x01 || code in 0xd0..0xd7) {
                output.writeShort(0xff00 or code)
                continue
            }
            val length = input.readUnsignedShort()
            require(length >= 2) { "Invalid JPEG segment length" }
            val payload = ByteArray(length - 2).also(input::readFully)
            val metadata = code == 0xfe || (code in 0xe0..0xef &&
                !(code == 0xe2 && payload.startsWithAscii("ICC_PROFILE\u0000")) &&
                !(code == 0xee && payload.startsWithAscii("Adobe")))
            if (metadata) removed++ else {
                output.writeShort(0xff00 or code)
                output.writeShort(length)
                output.write(payload)
            }
            if (code == 0xda) {
                while (true) {
                    val value = input.readUnsignedByte()
                    if (value != 0xff) output.write(value) else {
                        var next = input.readUnsignedByte()
                        while (next == 0xff) next = input.readUnsignedByte()
                        if (next == 0 || next in 0xd0..0xd7) {
                            output.write(0xff)
                            output.write(next)
                        } else {
                            marker = next
                            break
                        }
                    }
                }
            }
        }
    }

    private fun png(input: DataInputStream, output: DataOutputStream): Int {
        val signature = ByteArray(8).also(input::readFully)
        require(signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "Invalid PNG header" }
        output.write(signature)
        var removed = 0
        while (true) {
            val length = input.readInt()
            require(length >= 0) { "Invalid PNG chunk length" }
            val type = ByteArray(4).also(input::readFully)
            val name = type.toString(Charsets.US_ASCII)
            val metadata = name !in setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs", "acTL", "fcTL", "fdAT")
            if (!metadata) {
                output.writeInt(length)
                output.write(type)
            } else removed++
            transfer(input, if (metadata) null else output, length.toLong() + 4)
            if (name == "IEND") {
                require(length == 0) { "Invalid PNG end chunk" }
                return removed
            }
        }
    }

    private fun gif(input: DataInputStream, output: DataOutputStream): Int {
        val header = ByteArray(13).also(input::readFully)
        require(header.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) { "Invalid GIF header" }
        output.write(header)
        val packed = header[10].toInt() and 0xff
        if (packed and 0x80 != 0) transfer(input, output, (3 * (1 shl ((packed and 7) + 1))).toLong())
        var removed = 0
        while (true) {
            when (val marker = input.readUnsignedByte()) {
                0x3b -> { output.write(marker); return removed }
                0x2c -> {
                    output.write(marker)
                    val descriptor = ByteArray(9).also(input::readFully)
                    output.write(descriptor)
                    val local = descriptor[8].toInt() and 0xff
                    if (local and 0x80 != 0) transfer(input, output, (3 * (1 shl ((local and 7) + 1))).toLong())
                    output.write(input.readUnsignedByte()) // LZW minimum code size.
                    subBlocks(input, output)
                }
                0x21 -> {
                    val label = input.readUnsignedByte()
                    val size = input.readUnsignedByte()
                    val data = ByteArray(size).also(input::readFully)
                    val preserve = label == 0xf9 || label == 0x01 || (label == 0xff &&
                        data.toString(Charsets.US_ASCII) in setOf("NETSCAPE2.0", "ANIMEXTS1.0"))
                    if (preserve) {
                        output.write(marker); output.write(label); output.write(size); output.write(data)
                    } else removed++
                    if (size != 0) subBlocks(input, if (preserve) output else null)
                }
                else -> error("Invalid GIF block")
            }
        }
    }

    private fun subBlocks(input: DataInputStream, output: DataOutputStream?) {
        while (true) {
            val size = input.readUnsignedByte()
            output?.write(size)
            if (size == 0) return
            transfer(input, output, size.toLong())
        }
    }

    private fun webp(input: DataInputStream, output: DataOutputStream): Int {
        val header = ByteArray(12).also(input::readFully)
        require(header.startsWithAscii("RIFF") && header.copyOfRange(8, 12).startsWithAscii("WEBP")) { "Invalid WebP header" }
        val containerSize = littleEndian(header, 4) + 8L
        require(containerSize >= 12) { "Invalid WebP length" }
        output.write(header)
        var consumed = 12L
        var removed = 0
        while (consumed < containerSize) {
            require(containerSize - consumed >= 8) { "Invalid WebP chunk" }
            val chunk = ByteArray(8).also(input::readFully)
            val name = chunk.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val size = littleEndian(chunk, 4)
            val padded = size + (size and 1L)
            require(padded <= containerSize - consumed - 8) { "Invalid WebP chunk length" }
            val preserve = name in setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF", "ICCP")
            if (preserve) {
                output.write(chunk)
                if (name == "VP8X") {
                    require(size == 10L) { "Invalid WebP extended header" }
                    output.write(input.readUnsignedByte() and 0xf3) // Clear EXIF and XMP presence flags.
                    transfer(input, output, padded - 1)
                } else transfer(input, output, padded)
            } else {
                transfer(input, null, padded)
                removed++
            }
            consumed += 8 + padded
        }
        // scrub patches the RIFF length after streaming, without buffering pixels.
        output.flush()
        return removed
    }

    private fun littleEndian(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 0xff) shl (8 * index))
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        val prefix = value.toByteArray(Charsets.US_ASCII)
        return size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }

    private fun transfer(input: InputStream, output: OutputStream?, count: Long) {
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read > 0) { "Truncated image data" }
            output?.write(buffer, 0, read)
            remaining -= read
        }
    }
}
