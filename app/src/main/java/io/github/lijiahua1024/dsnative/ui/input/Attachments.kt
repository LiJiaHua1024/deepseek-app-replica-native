package io.github.lijiahua1024.dsnative.ui.input

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.DraftAttachment
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 一张照片（MediaStore 最近照片行） */
data class RecentPhoto(val id: String, val uri: Uri, val name: String, val mimeType: String)

/** 查询最近照片（MediaStore，按添加时间倒序）。 */
suspend fun queryRecentPhotos(context: Context, limit: Int = 40): List<RecentPhoto> =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
        )
        // ref RecentPhotos：同秒入序按 _id 倒序
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val out = mutableListOf<RecentPhoto>()
        try {
            resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idCol)
                    out += RecentPhoto(
                        id = id.toString(),
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameCol) ?: "photo-$id",
                        mimeType = cursor.getString(mimeCol) ?: "image/*",
                    )
                }
            }
        } catch (_: Exception) {
        }
        out
    }

/** 复制 content:// 源到缓存目录，返回 DraftAttachment。 */
suspend fun importToDraft(context: Context, uri: Uri, photoId: String? = null): DraftAttachment? =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var name = "file-${UUID.randomUUID()}"
            var mime = resolver.getType(uri) ?: ""
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                }
            }
            if (mime.isEmpty()) mime = guessMime(name)
            val dir = File(context.cacheDir, "attaches").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}-$name")
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            DraftAttachment(
                id = UUID.randomUUID().toString(),
                name = name,
                mimeType = mime,
                isImage = mime.startsWith("image/"),
                localPath = file.absolutePath,
                recentPhotoId = photoId,
            )
        } catch (_: Exception) {
            null
        }
    }

private fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "pdf" -> "application/pdf"
        else -> ""
    }
}

@Composable
private fun rememberThumb(producer: suspend () -> Bitmap?): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(initialValue = null, producer) {
        value = withContext(Dispatchers.IO) {
            try {
                producer()?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

private fun decodeScaled(path: String, reqSize: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    if (opts.outWidth <= 0) return null
    var sample = 1
    while (opts.outWidth / (sample * 2) >= reqSize) sample *= 2
    return BitmapFactory.decodeFile(
        path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun thumbFor(path: String, sizePx: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                decodeScaled(path, sizePx)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

@Composable
private fun photoThumb(resolver: ContentResolver, uri: Uri, sizePx: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val b = if (Build.VERSION.SDK_INT >= 29) {
                    resolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        resolver, ContentUris.parseId(uri),
                        MediaStore.Images.Thumbnails.MINI_KIND, null)
                }
                b?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

/**
 * ref AndroidAttachments panel：+ 按钮展开，位于输入卡片下方、占满屏宽
 *（ref margin-inline: -12px 相对卡片内边距）。
 */
@Composable
fun AttachmentsPanel(
    open: Boolean,
    photos: List<RecentPhoto>,
    selectedPhotoIds: Set<String>,
    onPhoto: (RecentPhoto) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
) {
    if (!open) return
    val c = DsTheme.colors
    val resolver = LocalContext.current.contentResolver
    Column(Modifier.fillMaxWidth()) {
        if (photos.isNotEmpty()) {
            // .recent: gap 8, padding 24 20 0；80x80 r16 border-l2 skeleton
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(photos, key = { _, p -> p.id }) { i, photo ->
                    val selected = photo.id in selectedPhotoIds
                    // CSS padding 不裁剪可视区：首/末项自带 20dp 外边距，行满宽显示
                    Box(
                        Modifier.padding(
                                start = if (i == 0) 20.dp else 0.dp,
                                end = if (i == photos.lastIndex) 20.dp else 0.dp)
                            .size(80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.bgSkeleton)
                            .border(1.dp, c.borderL2, RoundedCornerShape(16.dp))
                            .clickable { onPhoto(photo) },
                    ) {
                        photoThumb(resolver, photo.uri, 320)?.let {
                            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        if (selected) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                        }
                        // 选中徽标：17px 圆，白边，未选黑底白边 / 选中白底黑勾
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp)
                                .size(17.dp)
                                .shadow(2.dp, CircleShape, clip = false)
                                .clip(CircleShape)
                                .background(if (selected) Color.White else Color(0x52000000))
                                .border(1.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(painterResource(R.drawable.ic_check_badge), null,
                                    tint = Color.Unspecified, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        // .actions: 3 列 grid，gap 8，padding 20 20 32.8；按钮 h84 r18 currentColor 5%
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachmentAction(Modifier.weight(1f), R.drawable.ic_camera,
                Strings.get("androidCamera"), onCamera)
            AttachmentAction(Modifier.weight(1f), R.drawable.ic_image_outline,
                Strings.get("androidGallery"), onGallery)
            AttachmentAction(Modifier.weight(1f), R.drawable.ic_files_clip,
                Strings.get("androidFiles"), onFiles)
        }
    }
}

@Composable
private fun AttachmentAction(modifier: Modifier, icon: Int, label: String, onClick: () -> Unit) {
    val c = DsTheme.colors
    Column(
        modifier.height(84.dp).clip(RoundedCornerShape(18.dp))
            .background(c.labelPrimary.copy(alpha = 0.05f))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = c.labelPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 15.sp, color = c.labelPrimary)
    }
}

/**
 * ref AndroidDraftAttachments + DraftAttachments：卡片内 textarea 上方的
 * 80x80 缩略图行 + 图片附件专属快捷建议 chips。
 */
@Composable
fun DraftAttachmentsRow(
    items: List<DraftAttachment>,
    showSuggestions: Boolean,
    canSend: Boolean,
    onRemove: (DraftAttachment) -> Unit,
    onPreview: (DraftAttachment) -> Unit,
    onSuggestion: (String) -> Unit,
) {
    if (items.isEmpty()) return
    val c = DsTheme.colors
    Column {
        // [data-android] .attachments：padding 9 9 0，gap 10，item 80x80 r16
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 9.dp, end = 9.dp, top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEach { item ->
                Box(
                    Modifier.size(80.dp).clip(RoundedCornerShape(16.dp))
                        .background(c.bgSkeleton)
                        .border(1.dp, c.borderL2, RoundedCornerShape(16.dp))
                        .clickable { onPreview(item) },
                ) {
                    if (item.isImage) {
                        thumbFor(item.localPath, 320)?.let {
                            Image(it, item.name, Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop)
                        }
                    } else {
                        // 非图片：FileCard 简化（图标 + 文件名）
                        Column(
                            Modifier.align(Alignment.Center).padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(painterResource(R.drawable.ic_files_clip), item.name,
                                tint = c.labelSecondary, modifier = Modifier.size(20.dp))
                            Text(
                                item.name.substringAfterLast('/'),
                                fontSize = 9.sp, color = c.labelSecondary, maxLines = 2,
                            )
                        }
                    }
                    // [data-android] .remove：20x20 常显 #606164 圆底
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp)
                            .size(20.dp).clip(CircleShape).background(Color(0xFF606164))
                            .clickable { onRemove(item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_close), Strings.get("remove") ,
                            tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        if (showSuggestions) {
            val keys = listOf(
                "androidPhotoWhat", "androidPhotoAnalyze",
                "androidPhotoAdvice", "androidPhotoExplain",
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = 9.dp, end = 9.dp, top = 14.dp, bottom = 3.6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                keys.forEach { key ->
                    Row(
                        Modifier.height(32.dp)
                            .border(1.dp, c.borderL2, RoundedCornerShape(10.dp))
                            .clickable(enabled = canSend) { onSuggestion(Strings.get(key)) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(Strings.get(key), fontSize = 14.sp, color = c.labelSecondary)
                        Spacer(Modifier.width(6.dp))
                        Icon(painterResource(R.drawable.ic_arrow_right_16), null,
                            tint = c.labelSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * ref AndroidImageViewer 预览态：全屏 dialog（#111317），图片 contain 居中，
 * 点按关闭；右下 action 行（裁剪、可下载时保存）。
 */
@Composable
fun ImagePreviewDialog(
    item: DraftAttachment,
    onClose: () -> Unit,
    onCrop: () -> Unit = {},
    onDownload: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF111317))) {
            val bmp = thumbFor(item.localPath, 2048)
            if (bmp != null) {
                Image(
                    bmp, item.name,
                    Modifier.fillMaxSize().clickable(onClick = onClose),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(painterResource(R.drawable.ic_photo_error), null,
                        tint = Color(0xFFF7F9FC), modifier = Modifier.size(20.dp))
                    Text(Strings.get("androidDraftImageLoadFailed"),
                        color = Color(0xFFF7F9FC), fontSize = 14.sp)
                }
            }
            // .previewControls：右下，bottom 16 + nav inset，gap 8
            Row(
                Modifier.align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF20242B))
                        .clickable(onClick = onCrop)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_crop), null,
                        tint = Color(0xFFF7F9FC), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(Strings.get("androidCropImage"),
                        color = Color(0xFFF7F9FC), fontSize = 15.sp)
                }
                if (onDownload != null) {
                    Row(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF20242B))
                            .clickable(onClick = onDownload)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_download), null,
                            tint = Color(0xFFF7F9FC), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(Strings.get("androidSaveImage"),
                            color = Color(0xFFF7F9FC), fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
