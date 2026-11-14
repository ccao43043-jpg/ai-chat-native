package com.aichat.app.ui.image

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aichat.app.ui.common.ImageCache
import java.io.File

/**
 * 把生成结果落到相册。
 *
 * 走 [ImageCache] 取原始字节（`data:` 是 base64、`https:` 是直取），不重新下载一遍。
 * Android 10+ 用 `MediaStore.Images`（需要的话会自己在 `Pictures/AiChat` 建目录），
 * 10 以下没有分区存储，直接写 `Pictures/AiChat` 再广播扫描。
 */
object ImageSaver {

    private const val TAG = "ImageSaver"
    private const val ALBUM = "AiChat"

    /** 成功返回可展示的位置（如「相册/Pictures/AiChat」），失败返回 null。 */
    suspend fun save(context: Context, src: String, name: String? = null): String? {
        val bytes = ImageCache.bytes(src)
        if (bytes == null) {
            android.util.Log.w(TAG, "取不到图片字节：${src.take(64)}")
            return null
        }
        val fileName = name ?: ("aichat-" + System.currentTimeMillis() + ".png")
        return try {
            writeTo(context, fileName, bytes)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "写相册失败", t)
            null
        }
    }

    private fun writeTo(context: Context, fileName: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeOf(fileName))
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在相册里创建条目")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "相册/Pictures/$ALBUM"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) error("无法创建目录")
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        @Suppress("DEPRECATION")
        android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeOf(fileName)), null)
        return file.absolutePath
    }

    private fun mimeOf(fileName: String): String = when {
        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
        fileName.endsWith(".webp", true) -> "image/webp"
        else -> "image/png"
    }
}
