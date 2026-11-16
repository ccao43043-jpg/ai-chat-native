package com.aichat.app.data.convlog

import com.aichat.app.data.json.Js
import com.aichat.app.data.json.JsObj
import com.aichat.app.golden.Golden
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * structured-diff-plan 阶段 A 验收（docs/structured-diff-plan.md §4.A）：
 * diff 判等从键序敏感的 equal 切到键序无关的 semanticEqual（PR #66）后，
 *  1. 整个 golden 语料：prev/next 键全部重排后生成的 ops 与原顺序完全一致（旧 equal 会产生幻影 msg.edit）
 *  2. 仅键插入顺序不同的两个对话 → 0 op（oplog 体积不增的根因修复点）
 *  3. 真实内容变化仍产生 op（语义判等不漏报）
 *  4. Undef / null 边界与 JS 一致：缺键 ≡ 缺键；缺键 → 显式 null 是变化（JS stringify 亦如此判定）
 * 「ops 与 JS 基准逐条一致」由 ConvLogGoldenTest.diff 继续承担（golden 期望即 JS byte-equal 产物）。
 */
class ConvLogPhaseATest {
    private val C = JsConvLog.Companion

    private fun eachDiff(block: (label: String, prev: Any?, next: Any?) -> Unit) {
        val cs = Golden.cases("convlog", "diff")
        assertTrue("diff 没有用例", cs.isNotEmpty())
        cs.forEach { block(it.label, it.arg(0), it.arg(1)) }
    }

    /** ① 对整个 golden 语料：键序全重排 → ops 不变（语义比较：payload 键序随输入走，属预期） */
    @Test
    fun opsAreInvariantToKeyOrderAcrossGoldenCorpus() {
        eachDiff { label, prev, next ->
            val expected = C.diff(prev, next)
            val actual = C.diff(Js.sortKeys(prev), Js.sortKeys(next))
            assertTrue(
                "键序不应影响 ops（$label）\nexpected: ${Js.stringify(expected)}\nactual:   ${Js.stringify(actual)}",
                Js.semanticEqual(expected, actual),
            )
        }
    }

    /** ② 幻影编辑消除：仅键插入顺序不同、内容相同的对话，diff 为空 */
    @Test
    fun keyReorderOnlyChangeProducesNoOps() {
        val prev = JsObj(
            "id" to "c1", "title" to "t", "model" to "m", "updatedAt" to 1.0,
            "messages" to listOf(
                JsObj("id" to "m1", "role" to "user", "content" to "hello", "createdAt" to 1.0),
                JsObj("id" to "m2", "role" to "assistant", "content" to "hi", "createdAt" to 2.0),
            ),
        )
        val next = JsObj(
            "messages" to listOf(
                JsObj("createdAt" to 1.0, "content" to "hello", "role" to "user", "id" to "m1"),
                JsObj("createdAt" to 2.0, "content" to "hi", "role" to "assistant", "id" to "m2"),
            ),
            "updatedAt" to 1.0, "model" to "m", "title" to "t", "id" to "c1",
        )
        val ops = C.diff(prev, next)
        assertTrue("仅键序不同不应产生任何 op，实际：${Js.stringify(ops)}", ops.isEmpty())
    }

    /** ③ 真实变化不漏报 */
    @Test
    fun realContentChangeStillEmitsOps() {
        val prev = JsObj(
            "id" to "c1", "updatedAt" to 1.0,
            "messages" to listOf(JsObj("id" to "m1", "role" to "user", "content" to "hello", "createdAt" to 1.0)),
        )
        assertTrue("完全相同的对话不产生 op", C.diff(prev, Js.clone(prev)).isEmpty())

        val nextEdited = Js.clone(prev) as JsObj
        nextEdited["messages"] = listOf(JsObj("id" to "m1", "role" to "user", "content" to "changed", "createdAt" to 1.0))
        val ops = C.diff(prev, nextEdited)
        assertTrue("内容编辑必须产生 op，实际：${Js.stringify(ops)}", ops.isNotEmpty())
        assertTrue("必须含 msg.edit：${Js.stringify(ops)}", ops.any { (it as JsObj)["op"] == "msg.edit" })
    }

    /** ④ Undef/null 边界：缺键 → 显式 null 是变化；双缺键不是 */
    @Test
    fun undefNullBoundaryMatchesJs() {
        val base = JsObj("id" to "m1", "role" to "user", "content" to "x", "createdAt" to 1.0)
        val convPrev = JsObj("id" to "c1", "updatedAt" to 1.0, "messages" to listOf(base))
        val convAbsentToNull = JsObj(
            "id" to "c1", "updatedAt" to 1.0,
            "messages" to listOf(JsObj("id" to "m1", "role" to "user", "content" to "x", "createdAt" to 1.0, "error" to null)),
        )
        val ops = C.diff(convPrev, convAbsentToNull)
        assertTrue("缺键 → 显式 null 必须产生 op（与 JS stringify 语义一致）：${Js.stringify(ops)}", ops.isNotEmpty())

        val convNullToNull = JsObj(
            "id" to "c1", "updatedAt" to 1.0,
            "messages" to listOf(JsObj("id" to "m1", "role" to "user", "content" to "x", "createdAt" to 1.0, "error" to null)),
        )
        assertTrue("双显式 null 不产生 op", C.diff(convAbsentToNull, convNullToNull).isEmpty())
    }
}
