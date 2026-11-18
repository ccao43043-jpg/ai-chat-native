package com.aichat.app.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Diag] 的合约：它会被放在任何错误路径上（删文件失败、写设备 id 失败、坏 JSON 改名失败…），
 * 所以第一要求是**自己绝不能抛** —— 否则「降级继续」会变成「因为记日志而崩溃」。
 */
class DiagTest {

    @Test
    fun warnNeverThrowsWithoutCause() {
        val ok = runCatching { Diag.warn("Test", "没有异常对象的记录") }.isSuccess
        assertTrue(ok)
    }

    @Test
    fun warnNeverThrowsWithCause() {
        val ok = runCatching {
            Diag.warn("Test", "带了异常对象", IllegalStateException("boom"))
        }.isSuccess
        assertTrue(ok)
    }

    @Test
    fun warnToleratesAwkwardThrowables() {
        // message 为 null、类名奇怪的 throwable 都不该让日志本身炸掉
        val ok = runCatching {
            Diag.warn("Test", "message 为 null", RuntimeException())
            Diag.warn("Test", "", null)
        }.isSuccess
        assertTrue(ok)
    }
}