package com.xyz.ghost.overlay.menu

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.xyz.ghost.R
import com.xyz.ghost.overlay.ball.BallAnimator
import com.xyz.ghost.overlay.ball.BallPositionCalc
import com.xyz.ghost.util.LOG_TAG
import com.xyz.ghost.util.dpToPx

/**
 * 职责：管理扇形小球菜单的窗口生命周期、动画和点击路由。
 * 通过构造时注入的回调与外部解耦，不持有任何业务状态引用。
 */
class MiniMenuController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val overlayType: Int,
    private val handler: Handler,
    private val animator: BallAnimator,
    private val pos: BallPositionCalc,
    private val isAdSkipAuthorized: () -> Boolean,
    private val isAdSkipEnabled: () -> Boolean,
    private val onLockClick: () -> Unit,
    private val onAdSkipClick: () -> Unit
) {
    companion object {
        private const val AUTO_DISMISS_MS = 3000L
        private const val FAN_OFFSET_DP = 48
        private const val MINI_BALL_SIZE_DP = 40
        private const val MINI_OVERFLOW_DP = 8
    }

    var isOpen = false
        private set

    private var miniLockView: View? = null
    private var miniAdView: View? = null

    private val fanOffsetPx get() = context.dpToPx(FAN_OFFSET_DP)
    private val miniBallSizePx get() = context.dpToPx(MINI_BALL_SIZE_DP)
    private val miniOverflowPx get() = context.dpToPx(MINI_OVERFLOW_DP)
    private val miniWindowSizePx get() = miniBallSizePx + 2 * miniOverflowPx

    private val dismissRunnable = Runnable { hide(animate = true) }

    fun show(mainCX: Int, mainCY: Int, snappedToLeft: Boolean) {
        if (isOpen) return
        isOpen = true
        Log.d(LOG_TAG, "MiniMenu.show")

        val dx = if (snappedToLeft) fanOffsetPx else -fanOffsetPx

        miniLockView = createMiniBall(
            cx = mainCX + dx, cy = mainCY - fanOffsetPx,
            iconRes = R.drawable.ic_lock, bgRes = R.drawable.bg_ball, delayMs = 0L,
            onClick = onLockClick
        )

        val authorized = isAdSkipAuthorized()
        val adBg = when {
            !authorized        -> R.drawable.bg_mini_ball_disabled
            isAdSkipEnabled()  -> R.drawable.bg_ball_success
            else               -> R.drawable.bg_ball
        }
        miniAdView = createMiniBall(
            cx = mainCX + dx, cy = mainCY + fanOffsetPx,
            iconRes = R.drawable.ic_ad_skip, bgRes = adBg, delayMs = 80L,
            onClick = onAdSkipClick
        )

        handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    /** 手指按下主球时暂停自动关闭计时 */
    fun pauseAutoDismiss() { handler.removeCallbacks(dismissRunnable) }

    fun hide(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
        handler.removeCallbacks(dismissRunnable)
        Log.d(LOG_TAG, "MiniMenu.hide animate=$animate")
        removeView(miniLockView, animate); miniLockView = null
        removeView(miniAdView, animate);   miniAdView   = null
    }

    private fun createMiniBall(
        cx: Int, cy: Int, iconRes: Int, bgRes: Int, delayMs: Long, onClick: () -> Unit
    ): View {
        val view = LayoutInflater.from(context).inflate(R.layout.mini_ball_layout, null)
        view.findViewById<ImageView>(R.id.mini_ball_icon).apply {
            setImageResource(iconRes)
            setBackgroundResource(bgRes)
        }
        val params = WindowManager.LayoutParams(
            miniWindowSizePx, miniWindowSizePx, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx - miniOverflowPx - miniBallSizePx / 2
            y = cy - miniOverflowPx - miniBallSizePx / 2
        }
        view.scaleX = 0f; view.scaleY = 0f; view.alpha = 0f
        windowManager.addView(view, params)
        animator.animateMiniBallIn(view, delayMs)
        view.setOnClickListener {
            handler.removeCallbacks(dismissRunnable)
            onClick()
        }
        return view
    }

    private fun removeView(v: View?, animate: Boolean) {
        v ?: return
        if (animate) {
            animator.animateMiniBallOut(v) { try { windowManager.removeView(v) } catch (_: Exception) {} }
        } else {
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
    }
}