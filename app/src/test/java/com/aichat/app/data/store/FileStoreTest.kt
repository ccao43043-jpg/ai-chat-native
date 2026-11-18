package com.aichat.app.data.store

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 落盘层的合约测试。
 *
 * 为什么单独给 [JsonFileStore] 写测试：这是整个 App 唯一「写用户数据」的地方，
 * 而它此前一行测试都没有（见 docs/ASSESS-2026-11-17.md §2「用户数据路径」）。
 * 一旦 `set()` 的 tmp→bak→rename 走错，代价是用户所有对话。
 */
class FileStoreTest {

    private lateinit var dir: File
    private lateinit var store: JsonFileStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("store").toFile()
        store = JsonFileStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun setThenGetRoundTripsUtf8() {
        val text = """{"title":"海边渔村 · 灯塔","emoji":"🌊"}"""
        assertTrue(store.set("convs.json", text))
        assertEquals(text, store.get("convs.json"))
    }

    @Test
    fun missingFileReadsAsNull() {
        assertNull(store.get("nope.json"))
        assertFalse(store.exists("nope.json"))
    }

    /** 覆盖写必须先留 .bak：中途断电/崩，用户还能从 bak 找回上一版。 */
    @Test
    fun overwriteKeepsPreviousVersionInBak() {
        store.set("settings.json", "v1")
        store.set("settings.json", "v2")
        assertEquals("v2", store.get("settings.json"))
        assertEquals("v1", store.get("settings.json.bak"))
    }

    /** 追加型文件（oplog）不做 .bak：它们本来就是「只往后加」，备份反而是浪费与误导。 */
    @Test
    fun appendsToJsonlDoNotProduceBak() {
        store.set("conv_1.log.jsonl", "a\n")
        store.set("conv_1.log.jsonl", "a\nb\n")
        assertFalse(store.exists("conv_1.log.jsonl.bak"))
        assertEquals("a\nb\n", store.get("conv_1.log.jsonl"))
    }

    @Test
    fun appendAddsWithoutRewriting() {
        store.append("conv_1.log.jsonl", "line1\n")
        store.append("conv_1.log.jsonl", "line2\n")
        assertEquals("line1\nline2\n", store.get("conv_1.log.jsonl"))
    }

    @Test
    fun deleteRemovesFileAndBak() {
        store.set("conv_2.json", "x")
        store.set("conv_2.json", "y")
        store.delete("conv_2.json")
        assertFalse(store.exists("conv_2.json"))
        assertFalse(store.exists("conv_2.json.bak"))
    }

    @Test
    fun renameMovesAndReplacesTarget() {
        store.set("a.json", "A")
        store.set("b.json", "B")
        assertTrue(store.rename("a.json", "b.json"))
        assertEquals("A", store.get("b.json"))
        assertFalse(store.exists("a.json"))
        assertFalse(store.rename("missing.json", "c.json"))
    }

    /** 半成品 tmp 不该出现在 list() 里 —— 否则启动时的「枚举对话」会把写了一半的文件当成数据。 */
    @Test
    fun listHidesTmpFiles() {
        store.set("a.json", "1")
        File(dir, "half.json.tmp").writeText("partial")
        assertEquals(listOf("a.json"), store.list())
    }

    /**
     * 文件名来自会话 id / 世界 id，不可信：`f()` 会把非 [A-Za-z0-9_.-] 统统换成 `_`。
     * 这条守住「不会因为一个奇怪 id 写到目录外」。
     */
    @Test
    fun unusualNamesAreSanitizedRatherThanEscaping() {
        assertTrue(store.set("conv:1/../x.json", "hi"))
        assertEquals("hi", store.get("conv:1/../x.json"))
        assertEquals(listOf("conv_1_.._x.json"), store.list())
        // 目录外没有被写出任何东西
        assertFalse(File(dir.parentFile, "x.json").exists())
    }

    @Test
    fun failsGracefullyWhenDirectoryIsNotWritable() {
        val blocker = File(Files.createTempDirectory("blocker").toFile(), "file").apply { writeText("x") }
        // 父级是普通文件 → mkdirs 失败、写不进去，但必须返回 false 而不是抛异常
        val bad = JsonFileStore(File(blocker, "store"))
        assertFalse(bad.set("a.json", "1"))
        assertNull(bad.get("a.json"))
    }
}