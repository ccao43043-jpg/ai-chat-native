package com.aichat.app.ui.image

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.domain.ImageRequest
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.net.ImageGen
import com.aichat.app.net.ModelCatalog
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.common.rememberImageBitmap
import com.aichat.app.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 生图页。服务商、模型、尺寸、参考图、提示词、生成、预览、保存到相册。
 * 本次会话内保留最近若干条结果；从聊天「据此生图」来的提示词走 ImageDraft。
 */
@Composable
fun ImageScreen(onBack: (() -> Unit)? = null) {
    val deps = LocalUiDeps.current
    val settings by deps.settings.settings.collectAsStateWithLifecycle()
    val providers = remember(settings.providers) { ImageGen.listImageProviders(settings.providers) }
    val scope = rememberCoroutineScope()

    var providerId by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(ImageGen.DEFAULT_SIZE) }
    var prompt by remember { mutableStateOf("") }
    // 从聊天「据此生图」跳过来时带一条提示词。生图页每次切 Tab 都会重新进组合，
    // 所以用一次性信箱（`ImageDraft`），取完即清。
    LaunchedEffect(Unit) {
        val p = ImageDraft.take()
        if (!p.isNullOrBlank()) prompt = p
    }
    var busy by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Shot>>(emptyList()) }
    var providerMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var refs by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current

    // 相册选图 → 就地转 data URL（不落盘、不上传）。协议层早在 ImageGen.buildImageRequest 里
    // 支持参考图，缺的只是这个入口。
    val pickRefs = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImageGen.MAX_REF_IMAGES_HARD),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val added = uris.mapNotNull { RefImageLoader.toDataUrl(context, it) }
        if (added.isEmpty()) {
            error = "这几张图读不出来（格式不支持或超过 4MB）"
        } else {
            error = null
            // 硬上限 16；真正的每服务商上限在 ImageGen.buildImageRequest 里按 OpenRouter 16 / 其余 10 切。
            refs = (refs + added).take(ImageGen.MAX_REF_IMAGES_HARD)
        }
    }


    val provider: ProviderConfig? = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
    LaunchedEffect(provider?.id) {
        if (model.isBlank()) model = provider?.imageModel.orEmpty()
    }

    val effective = model.ifBlank { provider?.imageModel.orEmpty() }
    val flux = ImageGen.isFluxTierModel(effective, provider)
    val imageModels = remember(provider) {
        provider?.models.orEmpty().filter { ModelCatalog.classifyModel(it, provider?.modelMeta) == "image" }
    }

    // 没有真实进度百分比，只能按耗时给阶段文案（对齐旧版 progressStage）
    LaunchedEffect(busy) {
        if (!busy) {
            elapsed = 0
            stage = ""
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (true) {
            elapsed = System.currentTimeMillis() - start
            stage = ImageGen.progressStage(elapsed)
            delay(250)
        }
    }

    fun generate() {
        val p = provider
        if (p == null) {
            error = "请先在设置 → 服务商里添加一个 OpenAI 兼容服务商"
            return
        }
        if (prompt.isBlank()) {
            error = "先写点提示词"
            return
        }
        ImageGen.canGenerate(p, model).exceptionOrNull()?.let {
            error = it.message
            return
        }
        val client = deps.image
        if (client == null) {
            error = "生图客户端未就绪"
            return
        }
        error = null
        busy = true
        scope.launch {
            val r = client.generate(
                ImageRequest(
                    provider = p,
                    model = model,
                    prompt = prompt,
                    size = size,
                    refImages = refs,
                ),
            )
            busy = false
            r.fold(
                onSuccess = { urls ->
                    results = (listOf(Shot(prompt, model, size, urls, System.currentTimeMillis())) + results)
                        .take(ImageGen.HISTORY_LIMIT)
                },
                onFailure = { e -> error = e.message ?: "生成失败" },
            )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("image-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Spacer(Modifier.width(4.dp))
            }
            Text("生图", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "使用已配置的 OpenAI 兼容服务商。模型留空时用该服务商的生图模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Box {
            OptionCard(
                label = "服务商",
                value = provider?.name ?: "请先添加服务商",
                tag = "image-provider",
                onClick = { providerMenu = true },
            )
            DropdownMenu(providerMenu, onDismissRequest = { providerMenu = false }) {
                if (providers.isEmpty()) {
                    DropdownMenuItem(text = { Text("还没有服务商") }, onClick = { providerMenu = false })
                }
                providers.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = {
                            providerId = p.id
                            providerMenu = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // 用和上下两张 OptionCard 同形同高的可编辑卡片。
        // 原来这里是 OutlinedTextField：自带 56dp 高度 + supportingText，
        // 夹在两张卡片中间明显高出一截，整列的节奏被打断。
        InputCard(
            label = "模型",
            value = model,
            placeholder = provider?.imageModel?.takeIf { it.isNotEmpty() } ?: "留空则用服务商里的生图模型",
            tag = "image-model",
            onChange = { model = it },
        )
        if (imageModels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "可选：" + imageModels.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Box {
            OptionCard(
                label = "尺寸",
                value = ImageGen.sizeOptionLabel(size, flux),
                tag = "image-size",
                onClick = { sizeMenu = true },
            )
            DropdownMenu(sizeMenu, onDismissRequest = { sizeMenu = false }) {
                ImageGen.SIZES.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(ImageGen.sizeOptionLabel(s, flux)) },
                        onClick = {
                            size = s
                            sizeMenu = false
                        },
                    )
                }
            }
        }
        if (flux) {
            Spacer(Modifier.height(6.dp))
            Text(
                "按分辨率和比例发送",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { pickRefs.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.testTag("image-add-ref"),
            ) {
                Text(if (refs.isEmpty()) "添加参考图" else "参考图 ${refs.size}")
            }
            if (refs.isNotEmpty()) {
                TextButton(onClick = { refs = emptyList() }, modifier = Modifier.testTag("image-clear-ref")) {
                    Text("清空")
                }
            }
        }
        if (refs.isEmpty()) {
            Text(
                // 与 providers.js:781 一致：OpenRouter 16 张，其余服务商 10 张但只用第一张
                if (com.aichat.app.net.ProviderRules.isOpenRouterProvider(provider))
                    "最多 ${ImageGen.MAX_REF_IMAGES_HARD} 张（OpenRouter 图生图），长边会缩到 ${RefImageLoader.MAX_EDGE}px"
                else
                    "最多 ${ImageGen.MAX_REF_IMAGES} 张，只会发送第一张；长边会缩到 ${RefImageLoader.MAX_EDGE}px",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (refs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row {
                refs.forEachIndexed { i, url ->
                    Box {
                        val bmp = rememberImageBitmap(url)
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "参考图 ${i + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.padding(end = 8.dp).size(64.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        }
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                                .testTag("image-ref-$i")
                                .clickable { refs = refs.filterIndexed { j, _ -> j != i } },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        com.aichat.app.ui.common.SoftField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("提示词") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag("image-prompt"),
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { generate() },
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("image-generate"),
        ) {
            Text(if (busy) "生成中…" else "生成")
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("$stage ${ImageGen.formatElapsed(elapsed)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        if (results.isEmpty() && !busy) {
            Spacer(Modifier.height(24.dp))
            Text(
                "还没有生成过图片。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        results.forEach { shot ->
            Spacer(Modifier.height(20.dp))
            Text(shot.prompt.take(120), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${shot.model.ifEmpty { "（默认模型）" }} · ${shot.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            shot.urls.forEachIndexed { index, url ->
                Spacer(Modifier.height(8.dp))
                val bmp = rememberImageBitmap(url)
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "生成结果",
                        // 只按自身像素画会得到一张小图（256px 的图在 440dpi 上只有 93dp），
                        // 这里固定成 1:1 的方框铺满宽度，Crop 裁掉多余边
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { Text("加载图片…", style = MaterialTheme.typography.bodySmall) }
                }
                // 存相册：结果可能只有 URL 也可能整段 base64，走 ImageCache 已有字节
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            notice = null
                            scope.launch {
                                val where = ImageSaver.save(context, url, "aichat-${shot.at}-$index.png")
                                if (where == null) {
                                    error = "保存失败（图片可能已过期，或没有存储权限）"
                                } else {
                                    error = null
                                    notice = "已保存到「$where」"
                                }
                            }
                        },
                        modifier = Modifier.testTag("image-save-$index"),
                    ) { Text("保存到相册") }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private data class Shot(val prompt: String, val model: String, val size: String, val urls: List<String>, val at: Long)

@Composable
private fun OptionCard(label: String, value: String, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Palette.Surface,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 和 [OptionCard] 同形同高的可编辑行：卡片里放一个无边框输入框。
 * 这样「服务商 / 模型 / 尺寸」三行高度一致，不会因为 OutlinedTextField 自带的
 * 56dp 高度和 supportingText 而突然高出一截。
 */
@Composable
private fun InputCard(label: String, value: String, placeholder: String, tag: String, onChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Palette.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().testTag(tag),
                )
            }
        }
    }
}
