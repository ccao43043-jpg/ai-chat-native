package com.aichat.app.ui.settings

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 把 CSV 写到用户能找到的地方。
 *
 * Android 10+ 走 MediaStore 的 Downloads（出现在「文件 › 下载」里，卸载不删）；
 * 更低版本没有分区存储，退回到应用自己的外部目录（`Android/data/<pkg>/files/`），
 * 路径会一起返回给调用方去 toast。
 */
object CsvExport {

    /** 返回给用户看的位置描述；失败返回 null。 */
    fun save(context: Context, fileName: String, text: String): String? = runCatching {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(StandardCharsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            "下载/$fileName"
        } else {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = File(dir, fileName)
            f.writeBytes(bytes)
            f.absolutePath
        }
    }.getOrNull()
}
