package com.byebyemeta

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.net.Uri
import kotlinx.coroutines.ensureActive
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
        return sanitizeRemuxed(context, source, output, onProgress)
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
            val muxTracks = tracks.map { (_, format) ->
                removeContainerMetadata(format)
                muxer.addTrack(format)
            }
            muxer.start()
            started = true

            val totalDuration = tracks.sumOf { (_, format) ->
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
                } else {
                    1L
                }
            }
            var completedDuration = 0L

            tracks.forEachIndexed { trackPosition, (sourceTrack, format) ->
                coroutineContext.ensureActive()
                extractor.selectTrack(sourceTrack)
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
                } else {
                    1L
                }
                var buffer = ByteBuffer.allocateDirect(initialBufferSize(format))
                val info = MediaCodec.BufferInfo()

                while (true) {
                    coroutineContext.ensureActive()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    if (sampleSize > buffer.capacity()) {
                        buffer = ByteBuffer.allocateDirect(sampleSize * 2)
                        continue
                    }
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxTracks[trackPosition], buffer, info)
                    val localProgress = info.presentationTimeUs.coerceIn(0L, duration)
                    onProgress(((completedDuration + localProgress).toFloat() / totalDuration).coerceIn(0f, 0.98f))
                    extractor.advance()
                }
                extractor.unselectTrack(sourceTrack)
                completedDuration += duration
            }

            onProgress(0.99f)
            SanitizedMedia(output, "video/mp4", MediaKind.VIDEO)
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
        onProgress(1f)
        return result
    }

    private fun initialBufferSize(format: MediaFormat): Int {
        val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            256 * 1024
        }
        return max(maxInputSize, 64 * 1024)
    }

    private fun removeContainerMetadata(format: MediaFormat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf("location", "date", "creation-time", "com.android.capture.fps").forEach {
                format.removeKey(it)
            }
        }
    }
}
