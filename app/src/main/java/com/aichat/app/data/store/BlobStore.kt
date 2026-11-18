package com.aichat.app.data.store

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * B：图片外置 CAS（content-addressed storage）。Message.images 里的 `data:image/...;base64,…`
 * （一轮 10 图 ≈ 1.4–2.7 MB 灌进 oplog）换成 `img:<sha1>` 引用，字节落 `files/store/blobs/img_<sha1>.jpg`。
 *
 * - 双读兼容：读侧同时认 img: 引用与旧 data: URL。旧数据永不主动重写，除非该消息再次发送时被引擎懒迁移。
 * - 同字节去重：put 两次只落一个文件（sha 即文件名）。
 * - 原子写与 [JsonFileStore.set] 同一 idiom：tmp → fsync → ATOMIC_MOVE。
 * - mime 按魔数嗅探（ producers 一律压成 JPEG；PNG 兜底），后缀 .jpg 只给人看。
 */
class BlobStore(private val dir: File) {
    constructor(fs: FileStore) : this(File(fs.dir, "blobs"))

    companion object {
        const val PREFIX = "img:"
        private val SHA = Regex("^[0-9a-f]{40}$")
    }

    private val lock = ReentrantLock()

    fun shaOf(ref: String): String? {
        val s = ref.trim()
        if (!s.startsWith(PREFIX)) return null
        val sha = s.removePrefix(PREFIX)
        return if (SHA.matches(sha)) sha else null
    }

    fun fileOf(ref: String): File? = shaOf(ref)?.let { File(dir, "img_$it.jpg") }

    /** 内容寻址写入：返回 `img:<sha1>`；同字节已存在则不重写（去重）。 */
    fun put(bytes: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "img_$sha.jpg")
        lock.withLock {
            if (!target.exists()) {
                val tmp = File(dir, "img_$sha.jpg.tmp")
                runCatching {
                    tmp.outputStream().use { os -> os.write(bytes); os.fd.sync() }
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    tmp.delete()
                    if (!target.exists()) throw it
                }
            }
        }
        return PREFIX + sha
    }

    /** 双读：img: 引用读文件；data: URL 兼容解出原字节；其它（http 等）→ null，不抛。 */
    fun bytes(ref: String): ByteArray? {
        val s = ref.trim()
        fileOf(s)?.let { f -> return f.takeIf { it.exists() }?.readBytes() }
        if (s.startsWith("data:")) {
            val marker = ";base64,"
            val i = s.indexOf(marker)
            if (i < 0) return null
            return runCatching { Base64.getMimeDecoder().decode(s.substring(i + marker.length)) }.getOrNull()
        }
        return null
    }

    /** 给协议层/展示层：img: 与 data: 都归一成 data URL；http 等其它 → null（调用方原样透传）。 */
    fun dataUrl(ref: String): String? {
        val s = ref.trim()
        if (s.startsWith("data:")) return s
        val bytes = bytes(s) ?: return null
        return "data:" + mimeOf(bytes) + ";base64," + Base64.getEncoder().encodeToString(bytes)
    }

    /** 魔数嗅探：JPEG(FFD8) / PNG(89504E47) / WEBP(RIFF…WEBP) / GIF；未知回 image/jpeg（producers 只产这两种）。 */
    fun mimeOf(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> "image/webp"
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        else -> "image/jpeg"
    }

    /** 未引用 blob 回收（可选项，今晚不接调用方）：删掉不在 [keep] 里的 img_*.jpg。返回删除数。 */
    fun sweep(keep: Set<String>): Int {
        val names = dir.list()?.filter { it.startsWith("img_") && it.endsWith(".jpg") } ?: return 0
        var n = 0
        for (f in names) {
            val ref = PREFIX + f.removePrefix("img_").removeSuffix(".jpg")
            if (ref !in keep) { File(dir, f).delete(); n++ }
        }
        return n
    }
}

/**
 * UI 侧定位器：Compose 的 ImageLoader 是 object，拿不到 AppGraph；
 * AppGraph 启动时把 dataUrl 解析器挂进来（img: → data:），未挂时 img: 引用按「无法解析」渲染占位。
 */
object BlobGate {
    @Volatile var dataUrl: ((String) -> String?)? = null
}
