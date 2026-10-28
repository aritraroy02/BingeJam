package com.syncit.app.social

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import com.syncit.core.social.AvatarException
import com.syncit.core.social.AvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a picked image into the bytes we upload: a GIF exactly as it is (so it stays animated,
 * up to 1 MB), anything else as a small square JPEG.
 */
object AvatarImages {
    private const val SIDE_PX = 320
    private const val JPEG_QUALITY = 85

    suspend fun prepare(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        if (resolver.getType(uri) == "image/gif") {
            resolver.openInputStream(uri)?.use { readAtMost(it, AvatarStore.MAX_GIF_BYTES) }
                ?: throw AvatarException("Couldn't read that GIF")
        } else {
            val square = centerSquare(decode(context, uri))
            ByteArrayOutputStream().use { out ->
                square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }
    }

    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw AvatarException("That GIF is too big. GIFs can be up to 1 MB.")
        }
        return out.toByteArray()
    }

    /** Decoded at roughly twice the final size; ImageDecoder also applies the photo's rotation. */
    private fun decode(context: Context, uri: Uri): Bitmap {
        val target = SIDE_PX * 2
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(max(1, min(info.size.width, info.size.height) / target))
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sample = max(1, min(bounds.outWidth, bounds.outHeight) / target)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        } catch (e: Exception) {
            null
        } ?: throw AvatarException("Couldn't open that picture")
    }

    private fun centerSquare(source: Bitmap): Bitmap {
        val side = min(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        return if (side > SIDE_PX) square.scale(SIDE_PX, SIDE_PX) else square
    }
}
