package net.wastu.cleancopy

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun uri(file: File) = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    private fun directory() = File(context.cacheDir, "fixtures/test-" + System.nanoTime()).apply { mkdirs() }
    private fun sourceImage(directory: File, extension: String): File {
        val file = File(directory, "private_camera_name.$extension")
        val format = when (extension) { "png" -> Bitmap.CompressFormat.PNG; "webp" -> Bitmap.CompressFormat.WEBP; else -> Bitmap.CompressFormat.JPEG }
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff2288aa.toInt()) }
        file.outputStream().use { check(bitmap.compress(format, 95, it)) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, "PrivateCamera")
            setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "PrivateSerial")
            setAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME, "PrivateOwner")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:01 12:00:00")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(-6.2, 106.8)
            saveAttributes()
        }
        return file
    }

    @Test fun cleansJpegPngAndWebpIncludingTagsTheOldInspectorMissed() {
        val directory = directory()
        try {
            listOf("jpg", "png", "webp").forEach { extension ->
                val source = sourceImage(directory, extension)
                val before = ClipboardMetadataReader.inspect(context, uri(source))
                assertTrue("$extension metadata: ${before.fields}; warning: ${before.warning}", before.fields.any { it.label == "Camera owner" && it.value == "PrivateOwner" })
                assertTrue(before.fields.any { it.label == "Camera serial number" && it.value == "PrivateSerial" })
                val output = ImageSanitizer.sanitize(context, uri(source), File(directory, "clean.$extension")).getOrThrow()
                val exif = ExifInterface(output.file)
                ClipboardMetadataReader.privacyTags.forEach { (tag, _) -> assertNull("Remaining tag $tag in $extension", exif.getAttribute(tag)) }
                assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                val originalPixels = BitmapFactory.decodeFile(source.absolutePath)
                val cleanPixels = BitmapFactory.decodeFile(output.file.absolutePath)
                assertTrue("Pixels changed for $extension", originalPixels.sameAs(cleanPixels))
                originalPixels.recycle(); cleanPixels.recycle()
                val inspected = ClipboardMetadataReader.inspect(context, uri(output.file))
                assertNull(inspected.warning)
                assertTrue(inspected.fields.isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun outputNamesHonorThePreferenceAndCompressionDefaultsOff() {
        val directory = directory()
        val enabled = FilenameRewriteStore.isEnabled(context)
        try {
            val source = sourceImage(directory, "jpg")
            FilenameRewriteStore.setEnabled(context, false)
            assertEquals("private_camera_name", OutputNameStore.resolve(context, uri(source), 3))
            FilenameRewriteStore.setEnabled(context, true)
            assertEquals("3", OutputNameStore.resolve(context, uri(source), 3))
            context.getSharedPreferences("output_settings", Context.MODE_PRIVATE).edit().remove("compress_video").commit()
            assertFalse(VideoCompressionStore.isEnabled(context))
        } finally { FilenameRewriteStore.setEnabled(context, enabled); directory.deleteRecursively() }
    }

    private fun video(directory: File) = File(directory, "rotated.mp4").also { file ->
        instrumentation.context.assets.open("rotated-with-audio.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun videoInfo(file: File): Triple<Int, Long, Int> {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(file.absolutePath)
            extractor.setDataSource(file.absolutePath)
            val audio = (0 until extractor.trackCount).count { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertNotNull(retriever.getFrameAtTime(500_000)?.also { it.recycle() })
            return Triple(rotation, duration, audio)
        } finally { retriever.release(); extractor.release() }
    }

    @Test fun remuxKeepsRotationAudioDurationAndRemovesMetadata() = runBlocking {
        val directory = directory()
        try {
            val source = video(directory)
            val output = VideoSanitizer.sanitize(context, uri(source), File(directory, "clean.mp4"), false) {}
            val original = videoInfo(source)
            val cleaned = videoInfo(output.file)
            assertEquals(90, original.first)
            assertEquals(original.first, cleaned.first)
            assertEquals(original.third, cleaned.third)
            assertTrue(kotlin.math.abs(original.second - cleaned.second) < 200)
            assertTrue(ClipboardMetadataReader.inspect(context, uri(output.file)).fields.isEmpty())
            assertFalse(output.file.readBytes().toString(Charsets.ISO_8859_1).contains("PrivateTitle"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun compressionProducesAPlayableVideoWithAudio() = runBlocking {
        val directory = directory()
        try {
            val source = video(directory)
            val output = VideoSanitizer.sanitize(context, uri(source), File(directory, "compressed.mp4"), true) {}
            val cleaned = videoInfo(output.file)
            assertEquals(1, cleaned.third)
            assertTrue(cleaned.second >= 1800)
            assertTrue(ClipboardMetadataReader.inspect(context, uri(output.file)).fields.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptInputHasAnInspectionWarningAndFailsCleaning() {
        val directory = directory()
        try {
            val file = File(directory, "broken.jpg").apply { writeText("Not an image") }
            assertNotNull(ClipboardMetadataReader.inspect(context, uri(file)).warning)
            assertTrue(ImageSanitizer.sanitize(context, uri(file)).isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test fun sharesMixedMediaWithReadableGrantsToAnotherUid() {
        val directory = directory()
        val latch = CountDownLatch(1)
        var received = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                received = intent?.getBooleanExtra("readable", false) == true && intent.getIntExtra("count", 0) == 2
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("net.wastu.cleancopy.TEST_MEDIA_READ"), ContextCompat.RECEIVER_EXPORTED)
        try {
            val image = sourceImage(directory, "jpg")
            val video = video(directory)
            val outgoing = ClipboardHelper.shareMediaIntent(context, listOf(uri(image), uri(video)), listOf("image/jpeg", "video/mp4"))
            assertEquals(Intent.ACTION_SEND_MULTIPLE, outgoing.action)
            assertEquals("*/*", outgoing.type)
            outgoing.component = ComponentName(instrumentation.context.packageName, MediaReceiverActivity::class.java.name)
            outgoing.putExtra("replyPackage", context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 29) instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
            try { context.startActivity(outgoing) }
            finally { if (Build.VERSION.SDK_INT >= 29) instrumentation.uiAutomation.dropShellPermissionIdentity() }
            assertTrue("Receiver did not run", latch.await(15, TimeUnit.SECONDS))
            assertTrue("Receiver could not read shared media", received)
        } finally { context.unregisterReceiver(receiver); directory.deleteRecursively() }
    }

    @Test fun missingFilesCannotReportClipboardSuccess() {
        val directory = directory()
        try {
            val missing = uri(File(directory, "missing.mp4"))
            assertFalse(ClipboardHelper.copyMedia(context, listOf(missing)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun bundledRulesWorkWithoutDownloadedCatalogAndRespectRealExceptions() {
        val providers = context.assets.open("clearurls-rules.json").bufferedReader().use { LinkRuleStore.parse(it.readText()) }
        assertTrue(providers.size >= 200)
        assertEquals("https://x.com/example/status/1", LinkSanitizer.cleanUrl("https://x.com/example/status/1?s=46&t=tracker", providers, true).cleaned)
        assertEquals("https://www.amazon.com/dp/EXAMPLE", LinkSanitizer.cleanUrl("https://www.amazon.com/dp/EXAMPLE?tag=tracker-20", providers, true).cleaned)
        val protected = "https://example.cloudflare.com/path?utm_source=mail"
        assertEquals(protected, LinkSanitizer.cleanUrl(protected, providers, true).cleaned)
    }

    @Test fun savesAReadableMediaStoreDownload() {
        if (Build.VERSION.SDK_INT < 29) return
        val directory = directory()
        var saved: Uri? = null
        try {
            val source = sourceImage(directory, "jpg")
            saved = DownloadsSaver.saveToDownloads(context, uri(source), "cleancopy-test.jpg", "image/jpeg").getOrThrow()
            assertTrue(ClipboardHelper.isReadable(context, saved))
        } finally { saved?.let { context.contentResolver.delete(it, null, null) }; directory.deleteRecursively() }
    }
}
