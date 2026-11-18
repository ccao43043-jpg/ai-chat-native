package com.aichat.app.domain

import android.util.Log

/**
 * 静默失败的统一出口。
 *
 * 代码里有若干「出错了但必须继续」的地方（写设备 id、删临时文件、把损坏的 JSON 改名……）。
 * 这些地方**不该抛异常**，但也**不该完全没声音** —— 项目已经吃过一次亏：
 * 世界书一整个版本不生效，就是因为 `PluginRegistryImpl` 把插件异常吞进 errors 列表而没人看。
 *
 * 约定：
 * - 用 [warn] 而不是 `Log.e`：这些大多不是崩溃，是「降级继续」；
 * - `what` 写清楚**丢的是什么**，出问题时能在 logcat 里一眼看到；
 * - 不要在这里做重活（打点、写文件），它可能在任何线程、任何错误路径上被调用。
 *
 * 单测环境里 `android.util.Log` 由 `testOptions.unitTests.returnDefaultValues = true` 兜住，返回 0，不会炸。
 */
object Diag {
    private const val TAG = "AiChat"

    fun warn(scope: String, what: String, t: Throwable? = null) {
        runCatching {
            if (t == null) Log.w(TAG, "[$scope] $what")
            else Log.w(TAG, "[$scope] $what: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}