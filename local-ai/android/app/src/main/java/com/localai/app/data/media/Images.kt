package com.localai.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.localai.app.runtime.inference.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Photos attached to chats. Stored app-private (filesDir/chat_images) as JPEG ≤ 2048 px with EXIF rotation applied,
 * then converted to packed RGB at the model's input size only when a message is answered.
 */
object Images {
    private const val STORE_MAX = 2048          // high enough for on-device text recognition of a document photo

    fun dir(context: Context) = File(context.filesDir, "chat_images").apply { mkdirs() }

    /** A fresh content:// URI the camera app can write a photo to (ACTION_IMAGE_CAPTURE). */
    fun cameraTarget(context: Context): Pair<File, Uri> {
        val f = File(File(context.cacheDir, "camera").apply { mkdirs() }, "shot.jpg")
        f.delete()
        return f to FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    /** Copies, rotates and downsizes an image from the gallery or camera into app storage. Returns the stored file. */
    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds the decoder returns null by design; only a missing stream is an error.
        val stream = cr.openInputStream(uri) ?: error("Cannot open the image.")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This file is not an image Android can read." }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, STORE_MAX) }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Cannot decode the image.")
        val rotation = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
        bmp = scaleAndRotate(bmp, STORE_MAX, rotation)
        val out = File(dir(context), UUID.randomUUID().toString() + ".jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        out
    }

    /** Decodes a stored photo and packs it as RGB, longest side ≤ [maxSide] (512 in lite mode, 768 otherwise). */
    suspend fun toRgb(file: File, maxSide: Int): RgbImage? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide); inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val src = BitmapFactory.decodeFile(file.path, opts) ?: return@withContext null
        val bmp = scaleAndRotate(src, maxSide, 0)
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        RgbImage(packRgb(px), w, h)
    }

    /** ARGB ints → packed RGB bytes (what the native image encoder expects). */
    fun packRgb(px: IntArray): ByteArray {
        val rgb = ByteArray(px.size * 3)
        for (i in px.indices) { val p = px[i]; rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte() }
        return rgb
    }

    /** Largest power-of-two subsampling that keeps the longest side ≥ [target]. */
    fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1; val longest = maxOf(w, h)
        while (longest / (s * 2) >= target) s *= 2
        return s
    }

    /** Thumbnail for the chat bubble. */
    suspend fun thumbnail(file: File, maxSide: Int = 600): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) })
    }

    private fun scaleAndRotate(src: Bitmap, maxSide: Int, rotation: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        if (scale == 1f && rotation == 0) return src
        val m = Matrix().apply { postScale(scale, scale); if (rotation != 0) postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }
}
