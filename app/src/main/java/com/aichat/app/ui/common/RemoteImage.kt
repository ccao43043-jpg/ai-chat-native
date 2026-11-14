package com.aichat.app.ui.common

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aichat.app.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 最小图片加载器：不引 Coil（依赖表里没有，也不想为一张图加一整个图片库）。
 *
 * 支持两种 src：
 *  - `https://…`：OkHttp 直取（不带 Key —— 生图返回的通常是带签名的公开 URL）
 *  - `data:image/png;base64,…`：b64_json 内联结果
 *
 * 缓存按 src 存在进程内（[ImageCache]），滚动/重组不会重复下载。
 */
@Composable
fun rememberImageBitmap(src: String?): ImageBitmap? {
    var bitmap by remember(src) { mutableStateOf(src?.let { ImageCache.get(it) }) }
    LaunchedEffect(src) {
        if (src == null || bitmap != null) return@LaunchedEffect
        val loaded = ImageCache.load(src)
        if (loaded != null) bitmap = loaded
    }
    return bitmap
}

object ImageCache {
    private val max = 24
    private val map = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > max
    }

    @Synchronized
    fun get(src: String): ImageBitmap? = map[src]

    @Synchronized
    fun put(src: String, bmp: ImageBitmap) { map[src] = bmp }

    suspend fun load(src: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = bytes(src) ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()?.also { put(src, it) }
    }

    /** 原始字节（保存到相册时复用，避免为了存盘再下载一遍）。 */
    suspend fun bytes(src: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            if (src.startsWith("data:")) {
                val i = src.indexOf("base64,")
                if (i < 0) null else Base64.decode(src.substring(i + 7), Base64.DEFAULT)
            } else {
                val req = Request.Builder().url(src).build()
                HttpClients.base.newCall(req).execute().use { it.body?.bytes() }
            }
        }.getOrNull()
    }
}
