package net.wastu.cleancopy

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.net.Uri
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

object VideoSanitizer {
    suspend fun sanitize(
        context: Context,
        source: Uri,
        output: File,
        compressVideo: Boolean,
        onProgress: suspend (Float) -> Unit
    ): SanitizedMedia {
        if (compressVideo) {
            return VideoCompressor.sanitize(context, source, output, onProgress)
        }
        return try {
            sanitizeRemuxed(context, source, output, onProgress)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Unsupported MP4 codecs require conversion, preserving the original resolution.
            output.delete()
            VideoCompressor.sanitize(context, source, output, onProgress, compress = false)
        }
    }

    private suspend fun sanitizeRemuxed(
        context: Context,
        source: Uri,
        output: File,
        onProgress: suspend (Float) -> Unit
    ): SanitizedMedia {
        output.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        var descriptor: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var started = false

        val result = try {
            descriptor = context.contentResolver.openFileDescriptor(source, "r")
                ?: error("The video source could not be opened")
            extractor.setDataSource(descriptor.fileDescriptor)

            val tracks = (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    index to format
                } else {
                    null
                }
            }
            require(tracks.any { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) {
                "No supported video track was found"
            }

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val rotation = MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(descriptor.fileDescriptor)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } finally { retriever.release() }
            }
            muxer.setOrientationHint(rotation)
            val muxTracks = tracks.map { (_, format) ->
                removeContainerMetadata(format)
                muxer.addTrack(format)
            }
            muxer.start()
            started = true

            val totalDuration = tracks.maxOf { (_, format) ->
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
                } else {
                    1L
                }
            }
            val mapping = tracks.mapIndexed { index, (track, _) -> track to muxTracks[index] }.toMap()
            tracks.forEach { (track, _) -> extractor.selectTrack(track) }
            var buffer = ByteBuffer.allocateDirect(tracks.maxOf { initialBufferSize(it.second) })
            val writtenTracks = mutableSetOf<Int>()
            val info = MediaCodec.BufferInfo()
            while (extractor.sampleTrackIndex >= 0) {
                    coroutineContext.ensureActive()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val requiredSize = extractor.sampleSize
                        require(requiredSize <= 64 * 1024 * 1024) { "A video sample is too large" }
                        if (requiredSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect(requiredSize.toInt())
                    }
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted videos cannot be cleaned" }
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                    info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    val sourceTrack = extractor.sampleTrackIndex
                    muxer.writeSampleData(mapping.getValue(sourceTrack), buffer, info)
                    writtenTracks += sourceTrack
                    onProgress((info.presentationTimeUs.toFloat() / totalDuration).coerceIn(0f, 0.98f))
                    extractor.advance()
            }
            check(writtenTracks.size == tracks.size) { "A video or audio track could not be copied" }
            muxer.stop()
            started = false

            onProgress(0.99f)
            SanitizedMedia(output, "video/mp4", MediaKind.VIDEO, notes =
                if (tracks.size < extractor.trackCount) listOf("Additional subtitle or data tracks were omitted") else emptyList())
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
            extractor.release()
            descriptor?.close()
        }
        Mp4MetadataScrubber.scrub(output)
        validate(context, source, output)
        onProgress(1f)
        return result
    }

    private fun initialBufferSize(format: MediaFormat): Int {
        val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            8 * 1024 * 1024
        }
        require(maxInputSize <= 64 * 1024 * 1024) { "A video sample is too large" }
        return max(maxInputSize, 64 * 1024)
    }

    internal fun validate(context: Context, source: Uri, output: File) {
        fun tracks(uri: Uri): Pair<Int, Long> {
            val retriever = MediaMetadataRetriever()
            val extractor = MediaExtractor()
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    extractor.setDataSource(descriptor.fileDescriptor)
                    retriever.setDataSource(descriptor.fileDescriptor)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    check(duration > 0) { "The video has no readable duration" }
                    val audioTracks = (0 until extractor.trackCount).count {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    check((0 until extractor.trackCount).any {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }) { "The video track is missing" }
                    return audioTracks to duration
                } ?: error("The video could not be opened")
            } finally { retriever.release(); extractor.release() }
        }
        val original = tracks(source)
        val cleaned = tracks(Uri.fromFile(output))
        check(cleaned.first >= original.first) { "An audio track is missing from the output" }
        check(kotlin.math.abs(cleaned.second - original.second) <= maxOf(1000L, original.second / 20)) { "The output video duration changed unexpectedly" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val frame = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
                else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            checkNotNull(frame) { "The cleaned video could not be decoded" }.recycle()
        } finally { retriever.release() }
    }

    private fun removeContainerMetadata(format: MediaFormat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf("location", "date", "creation-time", "com.android.capture.fps").forEach {
                format.removeKey(it)
            }
        }
    }
}
