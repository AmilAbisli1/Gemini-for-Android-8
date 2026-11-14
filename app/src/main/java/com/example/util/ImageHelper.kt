package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessedAttachment(
    val bitmap: Bitmap,
    val localFilePath: String,
    val width: Int,
    val height: Int,
    val approxSizeKb: Int,
)

object ImageHelper {

    private const val MAX_DIMENSION_PX = 1280

    suspend fun processSelectedImage(
        context: Context,
        uri: Uri,
    ): Result<ProcessedAttachment> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver

            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open selected image"))

            val rawWidth = boundsOptions.outWidth
            val rawHeight = boundsOptions.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Unsupported or corrupted image file"))
            }

            var inSampleSize = 1
            while ((rawWidth / inSampleSize) > MAX_DIMENSION_PX * 2 ||
                (rawHeight / inSampleSize) > MAX_DIMENSION_PX * 2
            ) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext Result.failure(IllegalStateException("Failed to decode image"))

            val finalBitmap = scaleDownIfNeeded(decoded, MAX_DIMENSION_PX)
            if (finalBitmap !== decoded) {
                decoded.recycle()
            }

            val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
            val outFile = File(attachmentsDir, "img_${UUID.randomUUID()}.jpg")
            FileOutputStream(outFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            val sizeKb = (outFile.length() / 1024L).toInt().coerceAtLeast(1)
            Result.success(
                ProcessedAttachment(
                    bitmap = finalBitmap,
                    localFilePath = outFile.absolutePath,
                    width = finalBitmap.width,
                    height = finalBitmap.height,
                    approxSizeKb = sizeKb,
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadBitmapFromPath(path: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) return@withContext null
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleDownIfNeeded(source: Bitmap, maxDim: Int): Bitmap {
        val currentMax = max(source.width, source.height)
        if (currentMax <= maxDim) return source
        val scale = maxDim.toFloat() / currentMax.toFloat()
        val targetW = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }
}
