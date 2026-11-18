package com.aichat.app.data.backup
import com.aichat.app.data.store.BlobStore

import com.aichat.app.data.json.Js
import com.aichat.app.data.json.JsObj
import com.aichat.app.data.store.FileStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整库备份 / 恢复（spec F26，必做）。zip 内容 = store/ 下全部文件（原名原字节，含 .bak、.corrupt-<ts>、.migrated）+ `aichat-backup.json` 清单。
 * **不含 Key**：Key 在 SharedPreferences/Keystore，不在 store/；导出时额外剔除任何疑似明文 Key 的文件名（防御）。
 * 旧版导入路径（回退到 0.x）：解压后把文件放回 filesDir/store/ —— M4 实测并写进 Release 说明。
 */
class BackupService(private val fs: FileStore, private val now: () -> Long = System::currentTimeMillis) {
    /** zip 解包结果：文本文件（store 顶层）+ 二进制 blob（B 的 store/blobs/，UTF-8 String 会破坏字节） */
    class Contents(val files: Map<String, String>, val blobs: Map<String, ByteArray>)

    companion object {
        const val MANIFEST = "aichat-backup.json"
        const val FORMAT = "aichat-store-zip-v1"
        private val SAFE = Regex("^[A-Za-z0-9_.-]+$")
        /** zip 条目白名单：store 顶层 + B 的 blobs/ 一层（B 之前只有顶层 —— blobs 会被静默丢出备份） */
        private val ZIP_ENTRY = Regex("^(?:blobs/)?[A-Za-z0-9_.-]+$")
        private val NEVER = Regex("(?i)(secret|apikey|api_key|\\.keystore$|\\.jks$)")
        /** 导入时不覆盖、也不清除的本机文件 */
        val KEEP_LOCAL = setOf("device-id.json")
    }

    /** 写 zip，返回写入的文件数（不含清单） */
    fun exportZip(out: OutputStream): Int {
        val names = fs.list().filter { SAFE.matches(it) && !NEVER.containsMatchIn(it) && it != MANIFEST }.sorted()
        val blobDir = java.io.File(fs.dir, "blobs")
        val blobNames = (blobDir.list()?.filter { SAFE.matches(it) && !it.endsWith(".tmp") } ?: emptyList()).sorted()
        var n = 0
        ZipOutputStream(out).use { z ->
            for (name in names) {
                val text = fs.get(name) ?: continue
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry(); n++
            }
            // B：blobs/ 递归走原始字节 —— 文本 API 会破坏图片
            for (bn in blobNames) {
                val bytes = java.io.File(blobDir, bn).takeIf { it.isFile }?.readBytes() ?: continue
                z.putNextEntry(ZipEntry("blobs/" + bn)); z.write(bytes); z.closeEntry(); n++
            }
            val manifest = JsObj("format" to FORMAT, "at" to now().toDouble(), "count" to n.toDouble(), "files" to names.toMutableList<Any?>(), "blobs" to blobNames.toMutableList<Any?>(), "keysIncluded" to false)
            z.putNextEntry(ZipEntry(MANIFEST)); z.write(Js.stringify(manifest, 2)!!.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        return n
    }
    fun exportZip(file: File): Int { file.parentFile?.mkdirs(); return file.outputStream().use { exportZip(it) } }

    /** 读 zip 并校验（文件名白名单、无路径穿越、清单格式），返回 name → 内容；失败抛异常，不触碰现有数据 */
    fun readZip(input: InputStream): Contents {
        val files = LinkedHashMap<String, String>()
        val blobs = LinkedHashMap<String, ByteArray>()
        var manifest: Any? = null
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                require(ZIP_ENTRY.matches(name)) { "备份里有非法文件名：$name" }
                if (name == MANIFEST) { manifest = Js.parseOrNull(z.readBytes().toString(Charsets.UTF_8)); continue }
                if (name.startsWith("blobs/")) blobs[name.removePrefix("blobs/")] = z.readBytes()
                else files[name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        require(Js.prop(manifest, "format") == FORMAT) { "不是 AI Chat 整库备份（缺少 $MANIFEST）" }
        return Contents(files, blobs)
    }

    /** 用备份替换当前 store/（调用方须先做好当前数据的自动备份）。返回恢复的文件数 */
    fun restore(contents: Contents): Int {
        fs.list().filter { it !in KEEP_LOCAL && !it.endsWith(".tmp") }.forEach { fs.delete(it) }
        val blobDir = java.io.File(fs.dir, "blobs")
        blobDir.listFiles()?.forEach { if (it.name.endsWith(".jpg") || it.name.endsWith(".tmp")) it.delete() }
        var n = 0
        contents.files.forEach { (name, text) ->
            if (name in KEEP_LOCAL && fs.exists(name)) return@forEach
            // 直接写文件：.bak 原样恢复为 .bak，不再额外生成
            if (fs.set(name, text)) n++
        }
        val store = BlobStore(fs)
        contents.blobs.forEach { (_, bytes) -> store.put(bytes); n++ }
        // 先清空再写：目标文件都不存在，FileStore.set 不会额外生成 .bak；备份里的 .bak 条目原样恢复
        return n
    }
}
