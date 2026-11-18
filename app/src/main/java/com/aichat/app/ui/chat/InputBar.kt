package com.aichat.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aichat.app.data.files.FileText
import com.aichat.app.ui.common.ImageLoader
import com.aichat.app.ui.common.PdfPages
import com.aichat.app.ui.common.RemoteImage
import com.aichat.app.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 输入框工具条（提示词库 / 模板 / 联网搜索）的回调；null 表示不显示 */
data class InputTools(
    val onPrompts: (() -> Unit)? = null,
    val onTemplates: (() -> Unit)? = null,
    val templateLabel: String? = null,
    val searchOn: Boolean = false,
    val onSearch: ((Boolean) -> Unit)? = null,
)

/**
 * 输入区，布局对齐 Kimi：
 *   卡片**上方**一排功能胶囊（联网搜索 / 提示词 / 模板）→ 一条细长输入条（＋ ｜ 文本 ｜ 发送）
 *
 * 相比上一版把工具条塞进卡片里，这里把胶囊移出去 —— 好处是输入条只剩一行高（48dp），
 * 整个底部只占「一排胶囊 + 一条输入」，视觉上最接近参考图。
 */
@Composable
fun InputBar(
    text: String,
    onText: (String) -> Unit,
    images: List<String>,
    onImages: (List<String>) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
    files: List<JsonObject> = emptyList(),
    onFiles: (List<JsonObject>) -> Unit = {},
    fileSourceMax: Int = FileText.DEFAULT_FILE_MAX,
    tools: InputTools = InputTools(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachMenu by remember { mutableStateOf(false) }
    // 只认键盘，不掺焦点：焦点在键盘弹出前就变了，两段动画会错位
    val composing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var reading by remember { mutableStateOf(0) }
    // 回调里读最新值（launcher 回调在组合之外触发）
    val cur = remember { object { var images = images; var files = files } }
    cur.images = images; cur.files = files

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val urls = uris.mapNotNull { ImageLoader.uriToDataUrl(ctx, it) }
            if (urls.size < uris.size) onToast("有 ${uris.size - urls.size} 张图片读取失败")
            onImages((cur.images + urls).take(MAX_IMAGES))
        }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            reading += uris.size
            for (u in uris) {
                try {
                    val r = readAttachment(ctx, u, fileSourceMax)
                    if (r.images.isNotEmpty()) onImages((cur.images + r.images).take(MAX_IMAGES))
                    r.file?.let { onFiles(cur.files + it) }
                    r.notice?.let(onToast)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 含 OutOfMemoryError / StackOverflowError（超大或畸形文件）：只提示，不崩溃
                    onToast("「${displayName(ctx, u) ?: "文件"}」读取失败：${if (e is OutOfMemoryError) "文件太大" else e.message ?: "未知错误"}")
                } finally { reading -= 1 }
            }
        }
    }

    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
            // ── 附件缩略图（有才显示） ──
            if (images.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    images.forEachIndexed { i, src ->
                        Box {
                            RemoteImage(src, Modifier.size(60.dp).clip(RoundedCornerShape(com.aichat.app.ui.theme.Radii.Sm.dp)),
                                contentDescription = "待发送图片")
                            Box(
                                // 触控区 28dp，视觉仍是 20dp 的圆：小按钮不至于点不准
                                Modifier.align(Alignment.TopEnd).size(28.dp)
                                    .clickable { onImages(images.filterIndexed { j, _ -> j != i }) }
                                    .testTag("remove-image"),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.inverseSurface,
                                    shape = CircleShape,
                                    modifier = Modifier.size(20.dp).align(Alignment.TopEnd),
                                ) {
                                    Icon(
                                        Icons.Filled.Close, "移除图片",
                                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                                        modifier = Modifier.size(20.dp).padding(3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            FileChips(files, onRemove = { i -> onFiles(files.filterIndexed { j, _ -> j != i }) })
            if (reading > 0) {
                Text("正在读取文件…", style = MaterialTheme.typography.labelSmall, color = Palette.Fg3)
            }

            // 功能胶囊：键盘弹起时淡出 + 轻微上移「让位」，收起时淡入落回。
            //
            // 关键取舍：**只做透明度/位移，不动高度**。以前是 shrinkVertically 把这一行
            // 收掉，输入区高度变 36dp，LazyColumn 的可用高度跟着变，正在显示的
            // Markdown 气泡要逐帧重新测量排版 —— 系统键盘动画和这段高度动画抢同一批
            // 帧，所以怎么调都卡。空间固定后，这两段动画互不干扰。
            val hasTools = tools.onSearch != null || tools.onPrompts != null || tools.onTemplates != null
            val pillsAlpha by animateFloatAsState(
                targetValue = if (composing) 0f else 1f,
                animationSpec = tween(durationMillis = if (composing) 200 else 240, easing = LinearOutSlowInEasing),
                label = "pillsAlpha",
            )
            val pillsShift by animateDpAsState(
                targetValue = if (composing) (-6).dp else 0.dp,
                animationSpec = tween(durationMillis = if (composing) 200 else 240, easing = LinearOutSlowInEasing),
                label = "pillsShift",
            )
            if (hasTools) {
                Row(
                    Modifier.fillMaxWidth()
                        .graphicsLayer {
                            alpha = pillsAlpha
                            translationY = pillsShift.toPx()
                        }
                        .horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 淡出后（透明度趋 0）不再响应点击，避免「看不见却点得到」
                    val canTap = !composing
                    tools.onSearch?.let { f ->
                        ToolPill(Icons.Filled.Search, "联网搜索", tools.searchOn, "search-toggle") { if (canTap) f(!tools.searchOn) }
                    }
                    tools.onPrompts?.let { f -> ToolPill(Icons.Filled.Create, "提示词", false, "prompts-open") { if (canTap) f() } }
                    tools.onTemplates?.let { f ->
                        ToolPill(Icons.AutoMirrored.Filled.List, tools.templateLabel ?: "模板", tools.templateLabel != null, "templates-open") { if (canTap) f() }
                    }
                }
            }

            // ── 输入条：＋ ｜ 文本 ｜ 发送 ──
            val canSend = reading == 0 && (text.isNotBlank() || images.isNotEmpty() || files.isNotEmpty())
            // 高度恒为 48dp，不随键盘/焦点变化；只有真的换行（maxLines=6）才自然长高。
            // 这里刻意不加任何尺寸动画：打字时每多一行都插一段布局动画，
            // 正文气泡会跟着每帧重排，反而发涩。
            val minH = 48.dp
            val fieldH = 20.dp
            val topPad = 4.dp
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Palette.Surface2,
                modifier = Modifier.fillMaxWidth().heightIn(min = minH),
            ) {
                Row(
                    Modifier.padding(start = 4.dp, end = 6.dp, top = topPad, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Box(
                            Modifier.padding(top = 2.dp).size(36.dp).clip(CircleShape)
                                .background(Palette.Surface3)
                                .clickable { attachMenu = true }.testTag("attach"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Add, "添加附件", Modifier.size(20.dp), tint = Palette.Fg)
                        }
                        DropdownMenu(attachMenu, { attachMenu = false }) {
                            DropdownMenuItem(text = { Text("从相册选择") }, modifier = Modifier.testTag("attach-image"), onClick = {
                                attachMenu = false
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text("选择文件（PDF / Word / PPT / Excel / 文本）") }, modifier = Modifier.testTag("attach-file"), onClick = {
                                attachMenu = false; docPicker.launch(arrayOf("*/*"))
                            })
                        }
                    }
                    Box(Modifier.weight(1f).padding(vertical = 9.dp)) {
                        if (text.isEmpty()) {
                            Text("尽管问，或做个任务…", style = MaterialTheme.typography.bodyMedium, color = Palette.Fg3)
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onText,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Fg),
                            cursorBrush = SolidColor(Palette.Accent),
                            maxLines = 6,
                            // testTag('input') 必须挂在文本框本体上：performTextInput 需要
                            // RequestFocus 语义动作（容器节点没有，挂外层会让 5 个 UI 测试全挂）
                            modifier = Modifier.fillMaxWidth().heightIn(min = fieldH)
                                .testTag("input"),
                        )
                    }
                    if (streaming) {
                        Box(
                            Modifier.padding(bottom = 2.dp).size(36.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
                                .clickable(onClick = onStop).testTag("stop"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Close, "停止", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Box(
                            Modifier.padding(bottom = 2.dp).size(36.dp).clip(CircleShape)
                                .background(if (canSend) Palette.Grad else Brush.linearGradient(listOf(Palette.Surface3, Palette.Surface3)))
                                .clickable(enabled = canSend, onClick = onSend)
                                .testTag("send"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = if (canSend) Color.White else Palette.Fg3, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
    }
}

/** 悬空功能胶囊：44dp 高、全圆角、灰底无边框 */
@Composable
private fun ToolPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val fg = if (selected) Palette.Accent else Palette.Fg
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) Palette.AccentSoft else Palette.Surface2,
        modifier = Modifier.height(44.dp).testTag(tag),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = fg)
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

private const val MAX_IMAGES = 8
private const val MAX_FILE_BYTES = 40L * 1024 * 1024   // 同 app.js MAX_FILE_BYTES
private const val SCAN_PAGES = 4                        // 同 app.js renderPdfPages(min(pages, 4))

private class Attachment(val file: JsonObject?, val images: List<String>, val notice: String?)

private fun displayName(ctx: Context, u: Uri): String? = runCatching {
    ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull() ?: u.lastPathSegment

private suspend fun readAttachment(ctx: Context, u: Uri, sourceMax: Int = FileText.DEFAULT_FILE_MAX): Attachment {
    val name = displayName(ctx, u) ?: "文件"
    val mime = ctx.contentResolver.getType(u)
    if (FileText.classifyFile(name, mime) == "image") {
        val url = ImageLoader.uriToDataUrl(ctx, u) ?: throw IllegalStateException("图片读取失败")
        return Attachment(null, listOf(url), null)
    }
    val bytes = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(u)!!.use { ins ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0L
            while (true) { val n = ins.read(buf); if (n < 0) break; total += n
                if (total > MAX_FILE_BYTES) throw IllegalArgumentException("文件超过 40 MB，无法读取"); out.write(buf, 0, n) }
            out.toByteArray()
        }
    }
    val ex = withContext(Dispatchers.Default) { FileText.extract(bytes, name, mime) }
    if (ex.scanned) {
        val imgs = runCatching { PdfPages.render(ctx, bytes, minOf(ex.pages ?: SCAN_PAGES, SCAN_PAGES)) }.getOrDefault(emptyList())
        val json = JsonObject(FileText.toFileJson(name, bytes.size.toLong(), ex) + ("imagePages" to JsonPrimitive(imgs.size)))
        return Attachment(json, imgs, if (imgs.isNotEmpty()) "「$name」是扫描版 PDF，已把前 ${imgs.size} 页转成图片附上，需要支持图片识别的模型"
            else "「$name」是扫描版 PDF，没有可提取的文字，请先用 OCR 转成文字版")
    }
    if (ex.text.isBlank()) throw IllegalArgumentException("没有提取到文字")
    val json = FileText.toFileJson(name, bytes.size.toLong(), ex, sourceMax)
    val trunc = (json["truncated"] as? JsonPrimitive)?.content == "true"
    return Attachment(json, emptyList(), if (trunc) "「$name」较长，只发送前 $sourceMax 字" else null)
}
