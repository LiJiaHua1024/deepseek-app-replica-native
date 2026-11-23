package io.github.lijiahua1024.dsnative.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

private const val MAX_AVATAR_EDGE = 256

/**
 * Decodes, center-crops, and resizes a selected avatar image to a compact WebP data URL
 * matching the replica app's profile storage constraint (max 256px square).
 */
suspend fun encodeProfileAvatarFromUri(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        val decoded = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val minDim = min(info.size.width, info.size.height)
                    if (minDim > MAX_AVATAR_EDGE * 2) {
                        decoder.setTargetSampleSize((minDim / MAX_AVATAR_EDGE).coerceAtLeast(1))
                    }
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }
        }.getOrNull() ?: return@withContext null

        try {
            val w = decoded.width
            val h = decoded.height
            if (w <= 0 || h <= 0) return@withContext null
            val cropEdge = min(w, h)
            val cropped = Bitmap.createBitmap(
                decoded,
                (w - cropEdge) / 2,
                (h - cropEdge) / 2,
                cropEdge,
                cropEdge,
            )
            val targetEdge = min(cropEdge, MAX_AVATAR_EDGE)
            val scaled = if (cropEdge > targetEdge) {
                Bitmap.createScaledBitmap(cropped, targetEdge, targetEdge, true).also {
                    if (cropped !== decoded) cropped.recycle()
                }
            } else {
                cropped
            }
            try {
                val out = ByteArrayOutputStream()
                val fmt = if (Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                if (!scaled.compress(fmt, 85, out)) return@withContext null
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                "data:image/webp;base64,$b64"
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

private val avatarBitmapCache = object : LruCache<String, ImageBitmap>(8) {}

internal fun decodeAvatarBitmap(dataUrl: String?): ImageBitmap? {
    if (dataUrl.isNullOrBlank()) return null
    synchronized(avatarBitmapCache) {
        avatarBitmapCache.get(dataUrl)?.let { return it }
    }
    val marker = ";base64,"
    val idx = dataUrl.indexOf(marker)
    if (!dataUrl.startsWith("data:image/") || idx < 0) return null
    val bytes = runCatching {
        Base64.decode(dataUrl.substring(idx + marker.length), Base64.DEFAULT)
    }.getOrNull() ?: return null
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return bmp.asImageBitmap().also { img ->
        synchronized(avatarBitmapCache) {
            avatarBitmapCache.put(dataUrl, img)
        }
    }
}

@Composable
fun ProfileAvatarView(avatarDataUrl: String?, modifier: Modifier = Modifier) {
    val bmp = remember(avatarDataUrl) { decodeAvatarBitmap(avatarDataUrl) }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape),
        )
    } else {
        OfficialDefaultAvatar(modifier)
    }
}

@Composable
fun OfficialDefaultAvatar(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(CircleShape)) {
        drawCircle(Color(0xFFE5E5E5))
        val w = size.width
        val h = size.height
        val innerR = w * (10f / 24f)
        val clip = Path().apply {
            addOval(Rect(w / 2f - innerR, h / 2f - innerR, w / 2f + innerR, h / 2f + innerR))
        }
        drawContext.canvas.save()
        drawContext.canvas.clipPath(clip)
        val sil = Color(0xFFA2A4A6)
        drawCircle(
            color = sil,
            radius = w * (3.75f / 24f),
            center = Offset(w / 2f, h * (9.0f / 24f)),
        )
        drawOval(
            color = sil,
            topLeft = Offset(w * (4.0f / 24f), h * (14.5f / 24f)),
            size = Size(w * (16.0f / 24f), h * (12.0f / 24f)),
        )
        drawContext.canvas.restore()
    }
}
