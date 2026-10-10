package net.wastu.cleancopy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun CleanMediaPreview(uri: Uri?, kind: MediaKind, name: String, modifier: Modifier = Modifier, active: Boolean = true) {
    val context = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        if (uri == null) {
            Text("Preview unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (kind == MediaKind.VIDEO) {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            var error by remember(uri) { mutableStateOf(false) }
            val player = remember(uri) {
                ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(failure: androidx.media3.common.PlaybackException) { error = true }
                    })
                    setMediaItem(MediaItem.fromUri(uri))
                    playWhenReady = false
                    prepare()
                }
            }
            LaunchedEffect(active) { if (!active) player.pause() }
            DisposableEffect(player, lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) player.pause()
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer); player.release() }
            }
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } },
                update = { it.player = player }, modifier = Modifier.fillMaxSize())
            if (error) Text("Video preview unavailable. Try opening the saved file.", color = MaterialTheme.colorScheme.error)
        } else {
            var loading by remember(uri) { mutableStateOf(true) }
            val bitmap by produceState<Bitmap?>(null, uri) {
                value = withContext(Dispatchers.IO) { decodeCleanPreview(context, uri) }
                loading = false
            }
            if (bitmap == null) Text(if (loading) "Loading preview…" else "Preview unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Image(bitmap!!.asImageBitmap(), name.ifBlank { "Clean image preview" }, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize())
        }
    }
}

internal fun decodeCleanPreview(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = minOf(1f, 1200f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1200) options.inSampleSize *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        val matrix = Matrix().apply {
            if (exif?.isFlipped == true) postScale(-1f, 1f)
            postRotate((exif?.rotationDegrees ?: 0).toFloat())
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}.getOrNull()
