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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.domain.ImageRequest
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.net.ImageGen
import com.aichat.app.net.ModelCatalog
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.common.rememberImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 生图页（`imagegen.js` + 旧版 `renderImageGen` 的可用子集）。
 *
 * 做了：服务商选择、模型（手填 / 从 models 里挑）、尺寸档位、提示词、生成、进度文案、结果预览、本次会话历史（最多 10 条）
 * 没做：参考图上传、历史落盘、保存到相册、聊天里「据此生图」
 */
@Composable
fun ImageScreen() {
    val deps = LocalUiDeps.current
    val settings by deps.settings.settings.collectAsStateWithLifecycle()
    val providers = remember(settings.providers) { ImageGen.listImageProviders(settings.providers) }
    val scope = rememberCoroutineScope()

    var providerId by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(ImageGen.DEFAULT_SIZE) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Shot>>(emptyList()) }
    var providerMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var refs by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current

    // 相册选图 → 就地转 data URL（不落盘、不上传）。协议层早在 ImageGen.buildImageRequest 里
    // 支持参考图，缺的只是这个入口。
    val pickRefs = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImageGen.MAX_REF_IMAGES),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val added = uris.mapNotNull { RefImageLoader.toDataUrl(context, it) }
        if (added.isEmpty()) {
            error = "这几张图读不出来（格式不支持或超过 4MB）"
        } else {
            error = null
            refs = (refs + added).take(ImageGen.MAX_REF_IMAGES)
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
        Text("生图", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "只支持 OpenAI 兼容服务商；模型留空时用服务商配置里的生图模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Box {
            OutlinedButton(
                onClick = { providerMenu = true },
                modifier = Modifier.fillMaxWidth().testTag("image-provider"),
            ) { Text(provider?.let { "${it.name} · ${it.baseUrl}" } ?: "选择服务商") }
            DropdownMenu(providerMenu, onDismissRequest = { providerMenu = false }) {
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

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("生图模型") },
            placeholder = { Text(provider?.imageModel?.ifEmpty { "例如 gpt-image-1" } ?: "例如 gpt-image-1") },
            supportingText = if (imageModels.isNotEmpty()) {
                { Text("可选：" + imageModels.joinToString("、"), style = MaterialTheme.typography.bodySmall) }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("image-model"),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { sizeMenu = true }, modifier = Modifier.testTag("image-size")) {
                    Text(ImageGen.sizeOptionLabel(size, flux))
                }
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
                Spacer(Modifier.size(8.dp))
                Text(
                    "档位模型：按 resolution + aspect_ratio 发送",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickRefs.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.testTag("image-add-ref")) {
                Text(if (refs.isEmpty()) "加参考图" else "参考图 ${refs.size}")
            }
            if (refs.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { refs = emptyList() }, modifier = Modifier.testTag("image-clear-ref")) {
                    Text("清空")
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    "最多 ${ImageGen.MAX_REF_IMAGES} 张，会自动缩到长边 ${RefImageLoader.MAX_EDGE}px",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                        // 点一下删掉这一张
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
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("提示词") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag("image-prompt"),
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { generate() }, enabled = !busy, modifier = Modifier.testTag("image-generate")) {
                Text(if (busy) "生成中…" else "生成")
            }
            if (busy) {
                Spacer(Modifier.size(12.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("$stage ${ImageGen.formatElapsed(elapsed)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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
            shot.urls.forEach { url ->
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
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private data class Shot(val prompt: String, val model: String, val size: String, val urls: List<String>, val at: Long)
