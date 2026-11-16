package com.aichat.app.data.json

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2 阶段 A：diff 判等切到 `semanticEqual` 的语义边界。
 *
 * `JsConvLog.diff()` 原来用 `equal`（`stringify` 比较，键序敏感），切到 `semanticEqual`
 * （键排序后比较）后必须保证：键序无关、`Undef` ≠ `null`、对象里的 `Undef` 值等价于缺键。
 */
class JsSemanticTest {

    @Test
    fun semanticEqualIgnoresKeyOrder() {
        val a = JsObj("x" to 1.0, "y" to 2.0)
        val b = JsObj("y" to 2.0, "x" to 1.0)
        assertTrue("键序不同应判等", Js.semanticEqual(a, b))
        assertFalse("equal 仍按 stringify 比较，键序不同就 false", Js.equal(a, b))
    }

    @Test
    fun undefIsNotEqualToNull() {
        assertTrue(Js.semanticEqual(Undef, Undef))
        assertFalse(Js.semanticEqual(Undef, null))
        assertFalse(Js.semanticEqual(null, Undef))
    }

    @Test
    fun undefKeyIsTreatedAsAbsent() {
        val withUndef = JsObj("a" to 1.0, "b" to Undef)
        val without = JsObj("a" to 1.0)
        assertTrue("stringify 会跳过 Undef 值，二者等价", Js.semanticEqual(withUndef, without))
    }

    @Test
    fun arraysStayOrderSensitive() {
        val a = jsArr(1.0, 2.0)
        val b = jsArr(2.0, 1.0)
        assertFalse("数组顺序有意义，不能被当成键排序", Js.semanticEqual(a, b))
    }
}