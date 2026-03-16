package com.xyz.ghost.overlay.ball

class BallPositionCalc(
    val ballSizePx: Int,
    val overflowPx: Int,
    val marginPx: Int,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int
) {
    val windowSizePx = ballSizePx + 2 * overflowPx

    /** 50% 隐藏：球体视觉中心与屏幕边缘对齐 */
    fun hiddenX(left: Boolean) =
        if (left) -(overflowPx + ballSizePx / 2)
        else screenWidth() - overflowPx - ballSizePx / 2

    /** 完整展示：球体视觉边缘距屏幕边缘 marginPx */
    fun revealedX(left: Boolean) =
        if (left) marginPx - overflowPx
        else screenWidth() - marginPx - overflowPx - ballSizePx

    /** 根据当前 params.x 判断球更靠近哪侧，返回 snappedToLeft */
    fun snapSide(paramX: Int) =
        (paramX + overflowPx + ballSizePx / 2) < screenWidth() / 2

    fun clampX(x: Int) = x.coerceIn(hiddenX(true), hiddenX(false))
    fun clampY(y: Int) = y.coerceIn(0, screenHeight() - windowSizePx)
}