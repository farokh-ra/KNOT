package dev.twoperson.messenger.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.load
import coil.request.ImageRequest
import java.io.File

/**
 * One loader for every GIF surface (the gallery and chat bubbles).
 *
 * API 28+ uses the platform's [ImageDecoder], which starts an [AnimatedImageDrawable] as soon
 * as it is attached. Older releases fall back to Coil's Movie-backed GIF decoder. Neither path
 * waits for a tap: every GIF is animated.
 */
object GifLoader {

    @Volatile
    private var imageLoader: ImageLoader? = null

    private fun loader(context: Context): ImageLoader {
        val existing = imageLoader
        if (existing != null) return existing
        return synchronized(this) {
            imageLoader ?: ImageLoader.Builder(context.applicationContext)
                .components { add(GifDecoder.Factory()) }
                .build()
                .also { imageLoader = it }
        }
    }

    fun load(target: ImageView, source: Any?) {
        if (source == null) {
            target.setImageDrawable(null)
            return
        }
        val animated = animatedDrawable(target.context, source)
        if (animated != null) {
            target.setImageDrawable(animated)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (animated as? AnimatedImageDrawable)?.start()
            }
            return
        }
        val request = ImageRequest.Builder(target.context)
            .data(source)
            .size(512)
            .crossfade(false)
            .allowHardware(false)
            .listener(onError = { _, result ->
                Log.e("GifLoader", "Could not load $source", result.throwable)
            })
            .build()
        target.load(request, loader(target.context))
    }

    private fun animatedDrawable(context: Context, source: Any): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val decoderSource = when (source) {
                is File -> ImageDecoder.createSource(source)
                is Uri -> ImageDecoder.createSource(context.contentResolver, source)
                else -> return null
            }
            ImageDecoder.decodeDrawable(decoderSource)
        } catch (e: Exception) {
            Log.w("GifLoader", "Native GIF decode failed for $source", e)
            null
        }
    }
}
