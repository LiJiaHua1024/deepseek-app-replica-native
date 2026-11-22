package io.github.lijiahua1024.dsnative.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 公开 API 单边上限；单次请求含 >= 15 张图时用较小上限（ref imageUpload.ts）。 */
const val MAX_IMAGE_EDGE = 8192
const val MANY_IMAGES_MAX_EDGE = 4096
const val MANY_IMAGES_COUNT = 15

/** 官方 Android App 在 RGBA 位图超过 30 MiB 时将宽高各减半一次。 */
const val APP_DOWNSAMPLE_PIXELS = (30L * 1024L * 1024L) / 4L

data class ImageTargetSpec(
    val width: Int,
    val height: Int,
    val sampleFactor: Int,
    val decodeInSampleSize: Int,
    val needsTransform: Boolean,
)

data class PreparedImageUpload(
    val file: File,
    val uploadName: String,
    val mimeType: String,
    val maxEdge: Int,
    val converted: Boolean,
    val tempFile: File? = null,
)

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

fun isStandardJpegOrPng(header: ByteArray): Boolean {
    val isJpeg = header.size >= 3 &&
        header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
    val isPng = header.size >= 8 && PNG_MAGIC.indices.all { i -> header[i] == PNG_MAGIC[i] }
    return isJpeg || isPng
}

fun webpFilename(originalName: String): String {
    val dot = originalName.lastIndexOf('.')
    val stem = if (dot > 0) originalName.substring(0, dot) else originalName
    return "$stem.webp"
}

fun computeImageTargetSpec(
    naturalWidth: Int,
    naturalHeight: Int,
    standardFormat: Boolean,
    maxEdge: Int = MAX_IMAGE_EDGE,
    forceConversion: Boolean = false,
): ImageTargetSpec {
    val w0 = max(1, naturalWidth)
    val h0 = max(1, naturalHeight)
    val sample = if (standardFormat && w0.toLong() * h0.toLong() > APP_DOWNSAMPLE_PIXELS) 2 else 1
    val sampledW = max(1, w0 / sample)
    val sampledH = max(1, h0 / sample)
    val longest = max(sampledW, sampledH)
    val scale = min(1.0, maxEdge.toDouble() / longest.toDouble())
    val outW = max(1, (sampledW * scale).roundToInt())
    val outH = max(1, (sampledH * scale).roundToInt())
    val needsTransform = standardFormat || scale < 1.0 || forceConversion

    var inSample = 1
    while ((w0 / (inSample * 2)) >= outW && (h0 / (inSample * 2)) >= outH) {
        inSample *= 2
    }
    return ImageTargetSpec(
        width = outW,
        height = outH,
        sampleFactor = sample,
        decodeInSampleSize = inSample,
        needsTransform = needsTransform,
    )
}

private fun exifOrientation(path: String): Int = try {
    ExifInterface(path).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
} catch (_: Exception) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun isExifSwappedDimensions(orientation: Int): Boolean =
    orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE

private fun exifMatrix(orientation: Int): Matrix? {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.setRotate(90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.setRotate(-90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        else -> return null
    }
    return m
}

private fun decodeWithSampleFallback(path: String, initialSample: Int): Bitmap? {
    var sample = max(1, initialSample)
    repeat(3) {
        try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(path, opts)
        } catch (_: OutOfMemoryError) {
            sample *= 2
        }
    }
    return null
}

/**
 * 上传前检查图片分辨率与格式，按官方规则降采样/限边并按需转码为 WebP。
 * 生成的临时文件由调用方负责在使用后删除；若无法在本地解码（非强制转码），则保留原文件交给服务端处理。
 */
fun prepareImageForUpload(
    source: File,
    cacheDir: File,
    originalName: String,
    originalMime: String,
    maxEdge: Int = MAX_IMAGE_EDGE,
    forceConversion: Boolean = false,
): PreparedImageUpload {
    val header = ByteArray(12)
    val readCount = try {
        source.inputStream().use { it.read(header) }.coerceAtLeast(0)
    } catch (_: Exception) {
        0
    }
    val standardFormat = isStandardJpegOrPng(header.copyOf(readCount))

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    val rawW = bounds.outWidth
    val rawH = bounds.outHeight
    if (rawW <= 0 || rawH <= 0) {
        return PreparedImageUpload(
            file = source,
            uploadName = originalName,
            mimeType = originalMime,
            maxEdge = 0,
            converted = false,
        )
    }

    val orientation = exifOrientation(source.absolutePath)
    val swapped = isExifSwappedDimensions(orientation)
    val naturalW = if (swapped) rawH else rawW
    val naturalH = if (swapped) rawW else rawH

    val spec = computeImageTargetSpec(
        naturalWidth = naturalW,
        naturalHeight = naturalH,
        standardFormat = standardFormat,
        maxEdge = maxEdge,
        forceConversion = forceConversion,
    )
    if (!spec.needsTransform) {
        return PreparedImageUpload(
            file = source,
            uploadName = originalName,
            mimeType = originalMime,
            maxEdge = max(naturalW, naturalH),
            converted = false,
        )
    }

    val decoded = decodeWithSampleFallback(source.absolutePath, spec.decodeInSampleSize)
        ?: return PreparedImageUpload(
            file = source,
            uploadName = originalName,
            mimeType = originalMime,
            maxEdge = max(naturalW, naturalH),
            converted = false,
        )

    var current = decoded
    try {
        exifMatrix(orientation)?.let { matrix ->
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (rotated !== current) {
                current.recycle()
                current = rotated
            }
        }
        if (current.width != spec.width || current.height != spec.height) {
            val scaled = Bitmap.createScaledBitmap(current, spec.width, spec.height, true)
            if (scaled !== current) {
                current.recycle()
                current = scaled
            }
        }
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "upload_${UUID.randomUUID()}.webp")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val wrote = try {
            FileOutputStream(tmp).use { out ->
                current.compress(format, 90, out)
            }
        } catch (_: Exception) {
            false
        }
        if (!wrote || !tmp.exists() || tmp.length() == 0L) {
            tmp.delete()
            return PreparedImageUpload(
                file = source,
                uploadName = originalName,
                mimeType = originalMime,
                maxEdge = max(naturalW, naturalH),
                converted = false,
            )
        }
        return PreparedImageUpload(
            file = tmp,
            uploadName = webpFilename(originalName),
            mimeType = "image/webp",
            maxEdge = max(current.width, current.height),
            converted = true,
            tempFile = tmp,
        )
    } finally {
        if (!current.isRecycled) {
            current.recycle()
        }
    }
}
