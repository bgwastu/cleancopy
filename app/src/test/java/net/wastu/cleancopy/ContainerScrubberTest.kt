package net.wastu.cleancopy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class ContainerScrubberTest {
    private fun withFile(bytes: ByteArray, extension: String, test: (File) -> Unit) {
        val file = File.createTempFile("cleancopy-test", ".$extension")
        try { file.writeBytes(bytes); test(file) } finally { file.delete() }
    }
    private fun data(block: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use(block) }.toByteArray()
    private fun pngChunk(type: String, payload: ByteArray) = data {
        writeInt(payload.size); writeBytes(type); write(payload)
        val crc = CRC32().apply { update(type.toByteArray()); update(payload) }
        writeInt(crc.value.toInt())
    }
    private fun box(type: String, payload: ByteArray) = data { writeInt(payload.size + 8); writeBytes(type); write(payload) }
    private fun jpegSegment(marker: Int, payload: ByteArray) = data { writeShort(0xff00 or marker); writeShort(payload.size + 2); write(payload) }
    private fun webpChunk(type: String, payload: ByteArray) = type.toByteArray() +
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array() + payload + if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    @Test fun jpegStripsExifIptcExtendedXmpCommentsAndTrailerButKeepsEncodedScan() {
        val icc = jpegSegment(0xe2, "ICC_PROFILE\u0000color".toByteArray())
        val scan = jpegSegment(0xda, byteArrayOf(1, 1, 0, 0, 63, 0)) + byteArrayOf(4, 5, -1, 0, 6, -1, -48, 7)
        val original = byteArrayOf(-1, -40) + icc + jpegSegment(0xe1, "Exif\u0000private".toByteArray()) +
            jpegSegment(0xe1, "http://ns.adobe.com/xmp/extension/secret".toByteArray()) +
            jpegSegment(0xed, "IPTC private".toByteArray()) + jpegSegment(0xfe, "comment".toByteArray()) +
            scan + byteArrayOf(-1, -39) + "motion video private".toByteArray()
        withFile(original, "jpg") {
            assertEquals(5, ImageMetadataScrubber.scrub(it, "jpg"))
            assertArrayEquals(byteArrayOf(-1, -40) + icc + scan + byteArrayOf(-1, -39), it.readBytes())
        }
    }

    @Test fun pngRemovesTextExifTimeAndUnknownPrivateChunksPreservingPixelsAndAnimation() {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val pixels = pngChunk("IHDR", ByteArray(13)) + pngChunk("acTL", ByteArray(8)) + pngChunk("IDAT", byteArrayOf(1, 2, 3)) + pngChunk("IEND", byteArrayOf())
        val private = listOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME", "prIV").fold(byteArrayOf()) { bytes, name -> bytes + pngChunk(name, "secret".toByteArray()) }
        withFile(signature + private + pixels, "png") {
            assertEquals(6, ImageMetadataScrubber.scrub(it, "png"))
            assertArrayEquals(signature + pixels, it.readBytes())
        }
    }

    @Test fun gifPreservesFramesTextDelayAndLoopButRemovesCommentsAndXmp() {
        val header = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, -128, 0, 0, 0, 0, 0, -1, -1, -1)
        val loop = byteArrayOf(0x21, -1, 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0)
        val frame = byteArrayOf(0x21, -7, 4, 0, 10, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0)
        val comment = byteArrayOf(0x21, -2, 6) + "secret".toByteArray() + byteArrayOf(0)
        val xmp = byteArrayOf(0x21, -1, 11) + "XMP DataXMP".toByteArray() + byteArrayOf(6) + "secret".toByteArray() + byteArrayOf(0)
        val visibleText = byteArrayOf(0x21, 1, 12) + ByteArray(12) + byteArrayOf(5) + "Hello".toByteArray() + byteArrayOf(0)
        withFile(header + loop + comment + frame + xmp + visibleText + frame + byteArrayOf(0x3b), "gif") {
            assertEquals(2, ImageMetadataScrubber.scrub(it, "gif"))
            assertArrayEquals(header + loop + frame + visibleText + frame + byteArrayOf(0x3b), it.readBytes())
        }
    }

    @Test fun webpRemovesExifXmpAndRepairsLengthAndFlagsKeepingAnimation() {
        val vp8x = byteArrayOf(0x0e) + ByteArray(9)
        val pixels = webpChunk("ANIM", ByteArray(6)) + webpChunk("ANMF", ByteArray(18))
        val chunks = webpChunk("VP8X", vp8x) + webpChunk("EXIF", "secret".toByteArray()) + pixels + webpChunk("XMP ", "secret".toByteArray())
        val header = "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunks.size + 4).array() + "WEBP".toByteArray()
        withFile(header + chunks, "webp") {
            assertEquals(2, ImageMetadataScrubber.scrub(it, "webp"))
            val output = it.readBytes()
            assertEquals(output.size - 8, ByteBuffer.wrap(output, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertEquals(2, output[20].toInt())
            assertArrayEquals(pixels, output.copyOfRange(30, output.size))
        }
    }

    @Test fun mp4ClearsMetadataAndTimestampsWithoutChangingSamplesOrOffsets() {
        val timing = box("mvhd", ByteArray(4) + ByteArray(8) { 1 } + byteArrayOf(10, 20, 30, 40))
        val metadata = box("udta", box("name", "Private title".toByteArray()))
        val samples = box("mdat", byteArrayOf(1, 2, 3, 4, 5))
        val original = box("moov", timing + metadata) + samples
        withFile(original, "mp4") {
            Mp4MetadataScrubber.scrub(it)
            val output = it.readBytes()
            assertEquals(original.size, output.size)
            assertArrayEquals(samples, output.takeLast(samples.size).toByteArray())
            assertArrayEquals(ByteArray(8), output.copyOfRange(20, 28))
            assertFalse(output.toString(Charsets.ISO_8859_1).contains("Private title"))
            assertTrue(output.toString(Charsets.ISO_8859_1).contains("free"))
        }
    }

    @Test fun malformedContainersFailInsteadOfReportingSuccess() {
        withFile(box("mvhd", byteArrayOf(0)), "mp4") { assertThrows(IllegalArgumentException::class.java) { Mp4MetadataScrubber.scrub(it) } }
        withFile(byteArrayOf(-1, -40, -1, -31, 0, 1), "jpg") {
            val before = it.readBytes()
            assertThrows(Exception::class.java) { ImageMetadataScrubber.scrub(it, "jpg") }
            assertArrayEquals(before, it.readBytes())
        }
    }
}
