package com.aichat.app.data.store

import com.aichat.app.data.DataGraph
import com.aichat.app.data.backup.BackupService
import com.aichat.app.data.secret.FakeKeyVault
import com.aichat.app.domain.model.AuthType
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.domain.StreamEvent
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.engine.DefaultChatEngine
import com.aichat.app.net.AuthInjector
import com.aichat.app.net.DefaultProviderClient
import com.aichat.app.net.TinySseServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** B：图片外置 CAS —— put 去重 / 双读兼容 / 引用转换 / 懒迁移 / 备份灾备（含 blobs 原始字节） */
class BlobStoreTest {
    private val dir: File = Files.createTempDirectory("aichat-blobs-test").toFile()

    private fun jpegBytes(seed: Int = 1): ByteArray = ByteArray(64) { i -> ((i * 7 + seed) and 0xFF).toByte() }
    private fun dataUrlOf(bytes: ByteArray, mime: String = "image/jpeg") =
        "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    // 1. put 同字节两次只产生 1 个文件（内容寻址去重）
    @Test fun putDedupes() {
        val b = BlobStore(dir)
        val r1 = b.put(jpegBytes())
        val r2 = b.put(jpegBytes())
        assertEquals(r1, r2)
        assertEquals(1, dir.listFiles()!!.size)
    }

    // 2. put 返回的 sha1 与内容一致
    @Test fun putShaMatchesContent() {
        val b = BlobStore(dir)
        val bytes = jpegBytes(2)
        val ref = b.put(bytes)
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("img:$sha", ref)
        assertTrue(ref.matches(Regex("^img:[0-9a-f]{40}$")))
    }

    // 3. read("img:<sha>") 取回原字节
    @Test fun bytesRoundTripRef() {
        val b = BlobStore(dir)
        val bytes = jpegBytes(3)
        assertEquals(bytes.toList(), b.bytes(b.put(bytes))!!.toList())
    }

    // 4. read("data:image/jpeg;base64,...") 取回原字节（兼容路径）
    @Test fun bytesReadsLegacyDataUrl() {
        val b = BlobStore(dir)
        val bytes = jpegBytes(4)
        assertEquals(bytes.toList(), b.bytes(dataUrlOf(bytes))!!.toList())
        assertNull(b.bytes("data:text/plain,hello"))       // 非 base64 data URL → null
    }

    // 5. 坏引用（不存在的 sha / 空引用 / 非 blob 格式）返回 null，不抛
    @Test fun bytesBadRefReturnsNull() {
        val b = BlobStore(dir)
        assertNull(b.bytes("img:" + "0".repeat(40)))
        assertNull(b.bytes("img:not-a-sha"))
        assertNull(b.bytes("img:"))
        assertNull(b.bytes("https://example.com/x.jpg"))
        assertNull(b.bytes(""))
    }

    // 6. dataUrl：img: 归一成 data URL（mime 按魔数），data: 原样透传，http → null
    @Test fun dataUrlRoundTripAndPassthrough() {
        val b = BlobStore(dir)
        val bytes = jpegBytes(6)
        val ref = b.put(bytes)
        val url = b.dataUrl(ref)!!
        val back = Base64.getDecoder().decode(url.substringAfter(";base64,"))
        assertEquals(bytes.toList(), back.toList())
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        // PNG 魔数 → image/png
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + jpegBytes(7)
        assertTrue(b.dataUrl(b.put(png))!!.startsWith("data:image/png;base64,"))
        assertEquals(dataUrlOf(bytes), b.dataUrl(dataUrlOf(bytes)))   // 旧数据透传
        assertNull(b.dataUrl("https://example.com/x.jpg"))
    }

    // 7. 引擎：发送时 data: → img: 落盘；wire 侧还原成 data:；关掉 blobs 的引擎行为不变
    @Test fun engineConvertsAndResolves() = runBlocking {
        val server = TinySseServer()
        val keys = FakeKeyVault()
        val g = DataGraph.inMemory(keys = keys)
        val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).followRedirects(false).build()
        val client = DefaultProviderClient(AuthInjector(keys), http)
        val blobs = BlobStore(Files.createTempDirectory("aichat-blobs-eng").toFile())
        val base = server.baseUrl + "/api/v1"
        keys.register("or", base, AuthType.BEARER); keys.set("or", "sk-t")
        g.settings.update { it.copy(providers = listOf(ProviderConfig("or", "openrouter", "OR", ProviderType.OPENAI, base, listOf("m")))) }
        val conv = g.chats.create("or", "m")
        fun okHandler() = TinySseServer.Handler { _, ex ->
            ex.head(200)
            ex.text("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n")
            ex.text("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        }
        repeat(4) { server.handlers += okHandler() }   // handlers 是一次性队列：每个请求消费一个
        val bytes = jpegBytes(8)
        val url = dataUrlOf(bytes)
        val engine = DefaultChatEngine(g.chats, g.settings, client, EmptyPlugins(), blobs = blobs)
        val ev = withTimeout(10_000) { engine.send(conv.id, "看图", listOf(url)).toList() }
        assertTrue(ev.isNotEmpty())

        // 持久化的消息存 img: 引用；blob 落盘
        val saved = g.chats.load(conv.id)!!.messages.last { it.role == Role.USER }
        val ref = saved.images.single()
        assertTrue(ref.startsWith("img:"))
        assertEquals(bytes.toList(), blobs.bytes(ref)!!.toList())
        // 请求体里已还原成 data:（协议层形状不变）
        val body = server.requests.poll(2, TimeUnit.SECONDS)!!.body
        assertTrue(body.contains(url.substringAfter(",").take(24)))

        // 旧装配（blobs=null）：images 原样进持久化与请求
        val conv2 = g.chats.create("or", "m")
        val engine2 = DefaultChatEngine(g.chats, g.settings, client, EmptyPlugins())
        withTimeout(10_000) { engine2.send(conv2.id, "看图", listOf(url)).toList() }
        val saved2 = g.chats.load(conv2.id)!!.messages.first { it.role == Role.USER }
        assertEquals(url, saved2.images.single())
        server.requests.poll(2, TimeUnit.SECONDS)
        server.close()
    }

    // 8. 懒迁移：旧对话（data: 内联）在下一次发送时换成 img:，且 wire 仍能解析
    @Test fun lazyMigrationOnNextSend() = runBlocking {
        val server = TinySseServer()
        val keys = FakeKeyVault()
        val g = DataGraph.inMemory(keys = keys)
        val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).followRedirects(false).build()
        val client = DefaultProviderClient(AuthInjector(keys), http)
        val blobs = BlobStore(Files.createTempDirectory("aichat-blobs-mig").toFile())
        val base = server.baseUrl + "/api/v1"
        keys.register("or", base, AuthType.BEARER); keys.set("or", "sk-t")
        g.settings.update { it.copy(providers = listOf(ProviderConfig("or", "openrouter", "OR", ProviderType.OPENAI, base, listOf("m")))) }
        val bytes = jpegBytes(9)
        val url = dataUrlOf(bytes)
        val old = Message("m1", Role.USER, "旧消息", 0, listOf(url))
        val conv = g.chats.create("or", "m").copy(messages = listOf(old))
        g.chats.save(conv)
        server.handlers += TinySseServer.Handler { _, ex ->
            ex.head(200)
            ex.text("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n")
            ex.text("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        }
        val engine = DefaultChatEngine(g.chats, g.settings, client, EmptyPlugins(), blobs = blobs)
        withTimeout(10_000) { engine.send(conv.id, "hi", emptyList()).toList() }

        val after = g.chats.load(conv.id)!!
        val migrated = after.messages.first { it.id == "m1" }
        assertTrue(migrated.images.single().startsWith("img:"))
        assertEquals(bytes.toList(), blobs.bytes(migrated.images.single())!!.toList())
        server.close()
    }

    // 9. 备份灾备：导出 zip 含 blobs/（原始字节），导入后 read 仍成功（B 之前 blobs 会被静默丢出备份）
    @Test fun backupRoundTripsBlobs() {
        val storeDir = Files.createTempDirectory("aichat-bk-src").toFile()
        val fs = JsonFileStore(storeDir)
        fs.set("settings.json", "{\"a\":1}")
        fs.set("convs.json", "[]")
        val blobs = BlobStore(fs)
        val bytes = jpegBytes(10)
        val ref = blobs.put(bytes)
        val bs = BackupService(fs)
        val zip = ByteArrayOutputStream()
        val n = bs.exportZip(zip)
        assertEquals(3, n)   // settings + convs + 1 blob
        // zip 里能看到 blobs/ 条目（读侧白名单放行）
        val contents = bs.readZip(ByteArrayInputStream(zip.toByteArray()))
        assertTrue(contents.files.containsKey("settings.json"))
        assertEquals(1, contents.blobs.size)
        assertTrue(contents.blobs.getValue("img_" + ref.removePrefix("img:") + ".jpg").toList() == bytes.toList())
        // 导入到全新 store：文本与 blob 都回来
        val dstDir = Files.createTempDirectory("aichat-bk-dst").toFile()
        val fs2 = JsonFileStore(dstDir)
        val n2 = BackupService(fs2).restore(contents)
        assertEquals(3, n2)
        assertEquals("{\"a\":1}", fs2.get("settings.json"))
        assertEquals(bytes.toList(), BlobStore(fs2).bytes(ref)!!.toList())
    }

    // 10. sweep：删未引用 blob，保留 keep 集合
    @Test fun sweepRemovesUnreferenced() {
        val b = BlobStore(dir)
        val keep = b.put(jpegBytes(11))
        val drop = b.put(jpegBytes(12))
        assertNotEquals(keep, drop)
        assertEquals(1, b.sweep(setOf(keep)))
        assertEquals(jpegBytes(11).toList(), b.bytes(keep)!!.toList())
        assertNull(b.bytes(drop))
    }

    private class EmptyPlugins : com.aichat.app.domain.PluginRegistry {
        override fun attach(context: com.aichat.app.domain.PluginContext) {}
        override fun transformRequest(req: com.aichat.app.domain.BuiltRequest, ctx: com.aichat.app.domain.RequestContext) = req
        override val plugins: List<com.aichat.app.domain.ChatPlugin> = emptyList()
        override fun isActive(id: String, settings: com.aichat.app.domain.model.AppSettings, conv: Conversation?) = false
        override suspend fun runBeforeRequest(ctx: com.aichat.app.domain.RequestContext, status: (String?) -> Unit) = ctx
        override fun runAfterResponse(ctx: com.aichat.app.domain.RequestContext, result: com.aichat.app.domain.TurnResult): Conversation = ctx.conv
        override fun messageBadges(msg: Message, settings: com.aichat.app.domain.model.AppSettings, conv: Conversation) = emptyList<com.aichat.app.domain.Badge>()
        override fun headerBadges(settings: com.aichat.app.domain.model.AppSettings, conv: Conversation) = emptyList<com.aichat.app.domain.Badge>()
        override fun messageState(msg: Message, settings: com.aichat.app.domain.model.AppSettings, conv: Conversation) = com.aichat.app.domain.MessageState()
        override val errors: kotlinx.coroutines.flow.StateFlow<List<kotlinx.serialization.json.JsonObject>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    }
}
