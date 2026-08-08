package com.byebyemeta

import java.io.ByteArrayOutputStream
import java.io.File

object JpegMetadataScrubber {
    private val xmpSignatures = listOf(
        "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII),
        "http://ns.adobe.com/xmp/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    )

    fun scrubXmp(file: File) {
        val input = file.readBytes()
        if (input.size < 4 || input[0] != 0xFF.toByte() || input[1] != 0xD8.toByte()) return

        val output = ByteArrayOutputStream(input.size)
        output.write(input, 0, 2)
        var offset = 2
        while (offset < input.size) {
            if (input[offset] != 0xFF.toByte()) {
                output.write(input, offset, input.size - offset)
                break
            }
            val markerStart = offset
            while (offset < input.size && input[offset] == 0xFF.toByte()) offset++
            if (offset >= input.size) break
            val marker = input[offset].toInt() and 0xFF
            offset++

            if (marker == 0xDA || marker == 0xD9) {
                output.write(input, markerStart, input.size - markerStart)
                break
            }
            if (marker == 0xD8 || marker in 0xD0..0xD7) {
                output.write(input, markerStart, offset - markerStart)
                continue
            }
            if (offset + 2 > input.size) {
                output.write(input, markerStart, input.size - markerStart)
                break
            }

            val length = ((input[offset].toInt() and 0xFF) shl 8) or
                (input[offset + 1].toInt() and 0xFF)
            val segmentEnd = offset + length
            if (length < 2 || segmentEnd > input.size) {
                output.write(input, markerStart, input.size - markerStart)
                break
            }

            val payloadStart = offset + 2
            val payloadLength = length - 2
            val isXmp = marker == 0xE1 && xmpSignatures.any { signature ->
                payloadLength >= signature.size && input.startsWith(signature, payloadStart)
            }
            if (!isXmp) output.write(input, markerStart, segmentEnd - markerStart)
            offset = segmentEnd
        }

        file.outputStream().use { output.writeTo(it) }
    }

    private fun ByteArray.startsWith(prefix: ByteArray, offset: Int): Boolean =
        prefix.indices.all { index -> this[offset + index] == prefix[index] }
}
