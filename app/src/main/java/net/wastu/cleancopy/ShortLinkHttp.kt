package net.wastu.cleancopy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class ShortLinkResponse(
    val code: Int,
    val location: String?,
    val refresh: String?,
    val contentType: String?,
    val body: String?
)

internal interface ShortLinkHttp {
    fun get(url: String, userAgent: String, timeoutMs: Int, maxBytes: Int): ShortLinkResponse
}

internal object HttpShortLinkClient : ShortLinkHttp {
    override fun get(url: String, userAgent: String, timeoutMs: Int, maxBytes: Int): ShortLinkResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            connection.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")

            val code = connection.responseCode
            val contentType = connection.contentType
            val body = if (code in 200..299 &&
                (contentType == null || contentType.contains("html", ignoreCase = true))
            ) {
                runCatching { connection.inputStream.use { it.readAtMost(maxBytes) } }.getOrNull()
            } else {
                null
            }

            return ShortLinkResponse(
                code = code,
                location = connection.getHeaderField("Location"),
                refresh = connection.getHeaderField("Refresh"),
                contentType = contentType,
                body = body
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readAtMost(limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(buffer)
            if (read < 0) break
            val take = minOf(read, limit - total)
            output.write(buffer, 0, take)
            total += take
            if (take < read) break
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
