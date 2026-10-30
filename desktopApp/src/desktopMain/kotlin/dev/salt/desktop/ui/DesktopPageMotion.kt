package dev.salt.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

internal val DesktopPageOrder = listOf("首页", "全部歌曲", "扫描音乐", "专辑", "艺人", "统计", "连接", "歌词更新", "均衡器", "设置")

internal fun <T> AnimatedContentTransitionScope<T>.desktopPageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val curve = CubicBezierEasing(.2f, .8f, .2f, 1f)
    return (slideInHorizontally(tween(320, easing = curve)) { it * direction } + fadeIn(tween(220))) togetherWith
        (slideOutHorizontally(tween(320, easing = curve)) { -it * direction } + fadeOut(tween(240))) using SizeTransform(clip = true)
}
