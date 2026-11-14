package com.aichat.app.ui.image

/**
 * 聊天里「据此生图」→ 生图页的一次性提示词信箱。
 *
 * 用单例而不是把字符串一路传参：生图页在切 Tab 时会被重新创建（`SaveableStateProvider` 只保留
 * saved state，不保留 `remember`），所以在 `LaunchedEffect(Unit)` 里取一次即可。
 */
object ImageDraft {
    var pending: String? = null

    fun take(): String? {
        val p = pending
        pending = null
        return p
    }
}
