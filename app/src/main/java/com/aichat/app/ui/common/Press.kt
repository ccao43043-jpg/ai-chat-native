package com.aichat.app.ui.common

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.aichat.app.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * 系统是否允许动画（设置里把动画缩放关掉时为 false）。
 *
 * 只在「看不出来会以为是卡住」的地方用它跳过动画，别用它改逻辑。
 */
@Composable
fun rememberAnimationsOn(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            Settings.Global.getFloat(
                ctx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
}

/**
 * 按下去缩一点、松手弹回，并且按一下也保证看得见（最短停留）。
 *
 * 长按拖走的场景不会误触发：手指一离开缩放立刻回弹，不做延迟。
 */
@Composable
fun Modifier.pressScale(
    source: InteractionSource,
    enabled: Boolean = true,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val animOn = rememberAnimationsOn()
    val scale = remember { Animatable(1f) }
    val hold = pressed
    LaunchedEffect(hold) {
        if (!enabled || !animOn) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        if (hold) {
            scale.animateTo(0.975f, spring(stiffness = Spring.StiffnessHigh))
        } else {
            // 快点一下时，按压态还没来得及画就松开了，这里补一个最短停留
            delay(Motion.press.toLong())
            scale.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * 可点的行/块：按压反馈 + 触感 + 可选的次要长按动作。
 *
 * 用它代替裸 `clickable`，页面里的点击手感才会一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    haptic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val click: () -> Unit = {
        if (haptic) runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        onClick()
    }
    val inner = modifier
        .pressScale(source, enabled)
        .combinedClickable(
            interactionSource = source,
            indication = if (enabled) LocalIndication.current else null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = click,
        )
    androidx.compose.foundation.layout.Box(inner) { content() }
}
