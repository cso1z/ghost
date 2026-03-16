package com.xyz.ghost.overlay.ball

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.xyz.ghost.R
import com.xyz.ghost.adskip.AdSkipService
import com.xyz.ghost.overlay.menu.MiniMenuController
import com.xyz.ghost.overlay.protection.ProtectionController
import com.xyz.ghost.util.LOG_TAG
import com.xyz.ghost.util.dpToPx
import kotlin.math.abs

/**
 * 职责：悬浮球的完整编排——窗口生命周期、触摸手势识别、
 * 状态管理（隐藏/展示/锁定）、自动收起计时，
 * 通过 ProtectionController 和 MiniMenuController 委托子职责。
 */
class BallController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val overlayType: Int,
    private val handler: Handler,
    private val pos: BallPositionCalc,
    private val protection: ProtectionController,
    private val isAdSkipAuthorized: () -> Boolean,
    private val isAdSkipEnabled: () -> Boolean,
    private val onAdSkipToggle: () -> Unit
) {
    companion object {
        private const val UNLOCK_HOLD_MS = 1500L
        private const val SNAP_DURATION = 280L
        private const val REVEAL_DURATION = 180L
        private const val AUTO_HIDE_DELAY = 1500L
    }

    var ballView: View? = null
        private set
    var ballParams: WindowManager.LayoutParams? = null
        private set

    private var snappedToLeft = false
    private var isHidden = false
    private var isSuccessAnimating = false

    private val hideRunnable = Runnable { slideToHidden() }

    private val animator = BallAnimator(
        windowManager = windowManager,
        getBallView = { ballView },
        getBallParams = { ballParams }
    )

    private lateinit var miniMenu: MiniMenuController

    init {
        miniMenu = MiniMenuController(
            context = context,
            windowManager = windowManager,
            overlayType = overlayType,
            handler = handler,
            animator = animator,
            pos = pos,
            isAdSkipAuthorized = isAdSkipAuthorized,
            isAdSkipEnabled = isAdSkipEnabled,
            onLockClick = { onMiniLockClick() },
            onAdSkipClick = { onMiniAdSkipClick() }
        )
    }

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.ball_layout, null)
        val icon = view.findViewById<ImageView>(R.id.ball_icon)
        val progressRing = view.findViewById<View>(R.id.ball_progress)

        snappedToLeft = false
        isHidden = false
        isSuccessAnimating = false

        val params = WindowManager.LayoutParams(
            pos.windowSizePx, pos.windowSizePx, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pos.revealedX(snappedToLeft)
            y = context.dpToPx(120)
        }

        setupTouch(icon, progressRing, params)
        windowManager.addView(view, params)
        ballView = view
        ballParams = params
        updateBallIcon(icon)
        scheduleHide()
        Log.i(LOG_TAG, "BallController.show")
    }

    fun destroy() {
        handler.removeCallbacks(hideRunnable)
        animator.cancelSnap()
        miniMenu.hide(animate = false)
        ballView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        ballView = null
        ballParams = null
        Log.i(LOG_TAG, "BallController.destroy")
    }

    // ── 触摸处理 ─────────────────────────────────────────────────────

    private fun setupTouch(icon: ImageView, progressRing: View, params: WindowManager.LayoutParams) {
        var wasHiddenOnDown = false
        var isDragging = false
        var touchStartX = 0f
        var touchStartY = 0f
        var paramStartX = 0
        var paramStartY = 0
        var holdStartMs = 0L

        progressRing.alpha = 0f
        icon.alpha = 0.9f

        icon.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    wasHiddenOnDown = isHidden
                    isDragging = false
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    holdStartMs = 0L
                    animator.cancelSnap()
                    paramStartX = params.x
                    paramStartY = params.y

                    if (miniMenu.isOpen) miniMenu.pauseAutoDismiss()
                    cancelHide()
                    if (isHidden) revealBall() else animator.animateBallX(pos.revealedX(snappedToLeft), REVEAL_DURATION)

                    if (protection.isActive) {
                        holdStartMs = SystemClock.elapsedRealtime()
                        progressRing.alpha = 1f
                        progressRing.scaleX = 0f
                        progressRing.scaleY = 0f
                        progressRing.animate().scaleX(1f).scaleY(1f).setDuration(UNLOCK_HOLD_MS).start()
                    }
                    if (!isSuccessAnimating) icon.alpha = 1f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && !isSuccessAnimating && (abs(dx) > 8f || abs(dy) > 8f)) {
                        isDragging = true
                        holdStartMs = 0L
                        progressRing.animate().cancel()
                        progressRing.alpha = 0f
                        animator.cancelSnap()
                        paramStartX = params.x
                        if (miniMenu.isOpen) miniMenu.hide(animate = true)
                    }
                    if (isDragging) {
                        params.x = pos.clampX(paramStartX + dx.toInt())
                        params.y = pos.clampY(paramStartY + dy.toInt())
                        updateWindowLayout(params)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isSuccessAnimating) {
                        progressRing.animate().cancel()
                        progressRing.alpha = 0f
                        icon.alpha = 0.9f
                    }
                    if (isDragging) {
                        snapToNearestEdge(params)
                    } else {
                        val held = if (holdStartMs > 0) SystemClock.elapsedRealtime() - holdStartMs else 0L
                        when {
                            held >= UNLOCK_HOLD_MS -> playUnlockSuccess(icon, progressRing)
                            wasHiddenOnDown -> scheduleHide()
                            !protection.isActive && !isSuccessAnimating -> {
                                if (miniMenu.isOpen) { miniMenu.hide(animate = true); scheduleHide() }
                                else showMenu(params)
                            }
                            else -> scheduleHide()
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    // ── 菜单回调 ─────────────────────────────────────────────────────

    private fun onMiniLockClick() {
        miniMenu.hide(animate = false)
        activateProtection()
        scheduleHide()
    }

    private fun onMiniAdSkipClick() {
        onAdSkipToggle()
        miniMenu.hide(animate = true)
        scheduleHide()
    }

    // ── 保护层 ───────────────────────────────────────────────────────

    private fun activateProtection() {
        val ball = ballView ?: return
        val params = ballParams ?: return
        val icon = ball.findViewById<ImageView>(R.id.ball_icon) ?: return
        cancelHide()
        protection.activate(ball, params)
        updateBallIcon(icon)
    }

    // ── 解锁动画 ─────────────────────────────────────────────────────

    private fun playUnlockSuccess(icon: ImageView, progressRing: View) {
        isSuccessAnimating = true
        progressRing.animate().cancel()
        progressRing.alpha = 0f

        protection.deactivate()
        animator.vibrateSuccess(context)

        icon.alpha = 1f
        icon.setBackgroundResource(R.drawable.bg_ball_success)

        animator.animateUnlockSuccess(icon) {
            isSuccessAnimating = false
            icon.setBackgroundResource(R.drawable.bg_ball)
            updateBallIcon(icon)
            icon.alpha = 0.9f
            scheduleHide()
        }
    }

    // ── 位置 / 动画 ──────────────────────────────────────────────────

    private fun showMenu(params: WindowManager.LayoutParams) {
        val cx = params.x + pos.overflowPx + pos.ballSizePx / 2
        val cy = params.y + pos.overflowPx + pos.ballSizePx / 2
        miniMenu.show(cx, cy, snappedToLeft)
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        snappedToLeft = pos.snapSide(params.x)
        isHidden = false
        animator.animateBallX(pos.revealedX(snappedToLeft), SNAP_DURATION, DecelerateInterpolator())
        scheduleHide()
    }

    private fun revealBall() {
        isHidden = false
        animator.animateBallX(pos.revealedX(snappedToLeft), REVEAL_DURATION)
    }

    private fun slideToHidden() {
        isHidden = true
        animator.animateBallX(pos.hiddenX(snappedToLeft), SNAP_DURATION, DecelerateInterpolator())
    }

    private fun scheduleHide(delayMs: Long = AUTO_HIDE_DELAY) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delayMs)
    }

    private fun cancelHide() { handler.removeCallbacks(hideRunnable) }

    private fun updateBallIcon(icon: ImageView) {
        icon.setImageResource(
            if (protection.isActive) R.drawable.ic_lock else R.drawable.ic_shield
        )
    }

    private fun updateWindowLayout(params: WindowManager.LayoutParams) {
        ballView?.let { try { windowManager.updateViewLayout(it, params) } catch (_: Exception) {} }
    }
}