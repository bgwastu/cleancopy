package net.wastu.cleancopy

import java.io.InputStream
import java.io.ByteArrayOutputStream

internal fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(result.size() + read <= limit) { "The input was too large" }
        result.write(buffer, 0, read)
    }
    return result.toByteArray()
}
