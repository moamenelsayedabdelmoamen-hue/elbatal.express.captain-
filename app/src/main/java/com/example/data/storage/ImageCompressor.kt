package com.example.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max

/**
 * Compresses and normalizes orientation of Captain images (Profile, National ID Front/Back, Order Receipts)
 * prior to uploading to Firebase Storage while preserving crisp text legibility.
 */
object ImageCompressor {

    private const val DEFAULT_MAX_DIMENSION = 1920
    private const val DEFAULT_JPEG_QUALITY = 85
    private const val COMPRESSION_THRESHOLD_BYTES = 350 * 1024 // Compress if > 350 KB or non-JPEG

    suspend fun compressIfNeeded(
        context: Context,
        uri: Uri,
        mimeType: String,
        rawSizeBytes: Long,
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): ByteArray = withContext(Dispatchers.IO) {
        val rawBytes = openUriInputStream(context, uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("تعذر قراءة ملف الصورة المحدد")

        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException("ملف الصورة فارغ أو تالف")
        }

        // Decode bounds first to prevent OOM and verify valid image header
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)

        val originalWidth = boundsOptions.outWidth
        val originalHeight = boundsOptions.outHeight
        if (originalWidth <= 0 || originalHeight <= 0) {
            throw IllegalArgumentException("الملف المختار ليس صورة صالحة")
        }

        val isAlreadyCompactJpeg =
            (mimeType.equals("image/jpeg", ignoreCase = true) || mimeType.equals("image/jpg", ignoreCase = true)) &&
                rawBytes.size <= COMPRESSION_THRESHOLD_BYTES &&
                max(originalWidth, originalHeight) <= maxDimension &&
                getExifOrientationDegrees(context, uri) == 0

        if (isAlreadyCompactJpeg) {
            return@withContext rawBytes
        }

        var inSampleSize = 1
        val maxSide = max(originalWidth, originalHeight)
        if (maxSide > maxDimension) {
            val halfMaxSide = maxSide / 2
            while ((halfMaxSide / inSampleSize) >= maxDimension) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decodedBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
            ?: throw IllegalStateException("تعذر معالجة بيانات الصورة")

        val rotationDegrees = getExifOrientationDegrees(context, uri)
        val rotatedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                decodedBitmap,
                0,
                0,
                decodedBitmap.width,
                decodedBitmap.height,
                matrix,
                true
            )
            if (rotated != decodedBitmap) {
                decodedBitmap.recycle()
            }
            rotated
        } else {
            decodedBitmap
        }

        val currentMax = max(rotatedBitmap.width, rotatedBitmap.height)
        val finalBitmap = if (currentMax > maxDimension) {
            val ratio = maxDimension.toFloat() / currentMax.toFloat()
            val targetW = (rotatedBitmap.width * ratio).toInt().coerceAtLeast(1)
            val targetH = (rotatedBitmap.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(rotatedBitmap, targetW, targetH, true)
            if (scaled != rotatedBitmap) {
                rotatedBitmap.recycle()
            }
            scaled
        } else {
            rotatedBitmap
        }

        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(55, 92), outputStream)
        finalBitmap.recycle()

        val compressed = outputStream.toByteArray()
        if (compressed.isEmpty()) {
            throw IllegalStateException("فشل ضغط الصورة قبل الرفع")
        }
        compressed
    }

    internal fun openUriInputStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return null
            val f = File(path)
            if (f.exists()) FileInputStream(f) else null
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun getExifOrientationDegrees(context: Context, uri: Uri): Int {
        return try {
            openUriInputStream(context, uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
