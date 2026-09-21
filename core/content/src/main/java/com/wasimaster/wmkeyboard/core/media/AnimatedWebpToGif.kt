package com.wasimaster.wmkeyboard.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.io.File

/**
 * An animated WebP as an animated GIF, for every field that does not speak
 * WhatsApp's sticker type.
 *
 * Taking `image/webp` and playing an animated one are two different things,
 * and nearly every chat app does only the first: Messenger and Telegram both
 * accept the file and draw one frame of it. A field cannot be asked which
 * kind it is, but a GIF moves everywhere. It is a worse picture (see
 * [GifEncoder]) and the better answer all the same, because a sticker that
 * was picked for its animation and arrives still is the wrong sticker. See
 * [MediaMime.animatedGoesAsGif] for where the line is drawn.
 *
 * [AnimatedWebpReader] hands over the frames as stills and this draws them the
 * way a player would: each onto the canvas at its offset, blended or not,
 * with the rectangle of the one before cleared first when that one asked for
 * it. The finished canvas is what goes to the encoder, scaled down only when it
 * is larger than [MAX_SIDE].
 */
object AnimatedWebpToGif {

    /**
     * Long edge of the GIF: the sticker canvas, so a sticker goes out at the
     * size it is. That costs bytes GIF is not good with, and is kept anyway
     * because 512×512 is what chat apps go by when they decide to draw a
     * picture from a keyboard as a sticker and not as a photo in a bubble.
     */
    const val MAX_SIDE = 512

    /** Past this many frames, or this big a canvas, it is not a sticker and is left alone. */
    private const val MAX_FRAMES = 200
    private const val MAX_CANVAS = 2048

    /**
     * Writes [webp] to [target] as a GIF. False, with nothing left behind, when
     * the file is not an animated WebP or any frame of it does not decode.
     * Blocking, and slow enough (a palette and a compression pass per frame)
     * to keep off the main thread.
     */
    fun transcode(webp: File, target: File): Boolean {
        val animation = runCatching { AnimatedWebpReader.read(webp.readBytes()) }.getOrNull() ?: return false
        if (animation.frames.size > MAX_FRAMES || maxOf(animation.width, animation.height) > MAX_CANVAS) return false

        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(animation.width, animation.height))
        val outWidth = (animation.width * scale).toInt().coerceAtLeast(1)
        val outHeight = (animation.height * scale).toInt().coerceAtLeast(1)

        val canvasBitmap = Bitmap.createBitmap(animation.width, animation.height, Bitmap.Config.ARGB_8888)
        val scaledBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val part = File(target.parentFile, "${target.name}.part")
        var written = false
        try {
            target.parentFile?.mkdirs()
            val complete = part.outputStream().buffered().use { stream ->
                val encoder = GifEncoder(stream, outWidth, outHeight)
                val drawn = compose(animation, canvasBitmap, scaledBitmap) { pixels, durationMs ->
                    encoder.addFrame(pixels, durationMs)
                }
                encoder.finish()
                drawn
            }
            written = complete && part.renameTo(target) && target.length() > 0
        } catch (_: Exception) {
            // Nothing to say and nothing to keep: the caller falls back, and
            // the half-written file goes with the finally below.
        } finally {
            canvasBitmap.recycle()
            scaledBitmap.recycle()
            part.delete()
        }
        return written
    }

    private fun compose(
        animation: AnimatedWebpReader.Animation,
        canvasBitmap: Bitmap,
        scaledBitmap: Bitmap,
        onFrame: (IntArray, Int) -> Unit,
    ): Boolean {
        val canvas = Canvas(canvasBitmap)
        val scaled = Canvas(scaledBitmap)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val whole = Rect(0, 0, canvasBitmap.width, canvasBitmap.height)
        val target = Rect(0, 0, scaledBitmap.width, scaledBitmap.height)
        val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        var toClear: Rect? = null
        for (frame in animation.frames) {
            toClear?.let { canvas.drawRect(it, clear) }
            val still = BitmapFactory.decodeByteArray(frame.still, 0, frame.still.size) ?: return false
            val at = Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height)
            canvas.drawBitmap(still, null, at, if (frame.blend) null else replace)
            still.recycle()
            toClear = at.takeIf { frame.disposeToBackground }

            scaled.drawBitmap(canvasBitmap, whole, target, smooth)
            scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)
            onFrame(pixels, frame.durationMs)
        }
        return true
    }
}
