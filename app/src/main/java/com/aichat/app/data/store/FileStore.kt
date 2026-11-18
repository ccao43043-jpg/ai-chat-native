package com.aichat.app.data.store

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * filesDir/store/ 的唯一读写入口（单一写入方）。语义与旧 NativeBridge.store* 逐字一致：
 *  - 文件名把 [^A-Za-z0-9_.-] 替换为 '_'
 *  - set：写 name.tmp → fsync → 非 .jsonl/.bak 时旧文件改名 name.bak → tmp 改名为 name
 *  - append：追加写（JSONL）
 *  - list：不含 *.tmp
 * v1.0.0 无内置网页入口，本对象是唯一写入方；所有仓库（D）经它读写，UI/net/domain 不得直接碰 File。
 */
interface FileStore {
    fun get(name: String): String?
    fun set(name: String, value: String): Boolean
    fun append(name: String, text: String): Boolean
    fun delete(name: String)
    fun exists(name: String): Boolean
    fun rename(from: String, to: String): Boolean
    fun list(): List<String>
    val dir: File
}

class JsonFileStore(override val dir: File) : FileStore {
    private val lock = ReentrantLock()
    init { if (!dir.exists()) dir.mkdirs() }

    private fun f(name: String) = File(dir, name.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    override fun get(name: String): String? = f(name).takeIf { it.exists() }?.let {
        runCatching { it.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun set(name: String, value: String): Boolean = lock.withLock {
        val target = f(name)
        val tmp = File(target.path + ".tmp")
        try {
            tmp.outputStream().use { os -> os.write(value.toByteArray(Charsets.UTF_8)); os.fd.sync() }
        } catch (e: Exception) { tmp.delete(); return false }
        val jsonl = target.name.endsWith(".jsonl") || target.name.endsWith(".bak")
        if (target.exists() && !jsonl) {
            val bak = File(target.path + ".bak")
            if (bak.exists()) bak.delete()
            if (!target.renameTo(bak)) target.delete()
        }
        moveInto(tmp, target)
    }

    /**
     * 把 tmp 原子地换到目标位置。
     *
     * 原先直接 `tmp.renameTo(target)`：Android（Linux）上覆盖已存在文件是原子的，
     * 但 Windows 与部分文件系统上**覆盖已存在文件会直接失败且返回 false**，
     * 于是 `set()` 对已有文件（尤其是追加型的 .jsonl）静默不生效 —— 单测就是这样抓到的。
     */
    private fun moveInto(tmp: File, target: File): Boolean =
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrElse {
            // 文件系统不支持 ATOMIC_MOVE 时退化成「先删再改名」，与 .bak 路径同样的取舍
            runCatching { target.delete(); tmp.renameTo(target) }.getOrDefault(false)
        }

    override fun append(name: String, text: String): Boolean = lock.withLock {
        runCatching { java.io.FileOutputStream(f(name), true).use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
    }

    override fun delete(name: String) = lock.withLock { val t = f(name); t.delete(); File(t.path + ".bak").delete(); Unit }
    override fun exists(name: String) = f(name).exists()
    override fun rename(from: String, to: String): Boolean = lock.withLock {
        val a = f(from); val b = f(to)
        if (!a.exists()) return false
        if (b.exists()) b.delete()
        moveInto(a, b)
    }
    override fun list(): List<String> = dir.list()?.filterNot { it.endsWith(".tmp") } ?: emptyList()
}
