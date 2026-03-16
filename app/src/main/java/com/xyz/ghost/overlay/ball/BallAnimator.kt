package com.xyz.ghost.overlay.ball

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView

/**
 * 职责：封装所有动画逻辑，与业务状态完全解耦。
 * - 主球 X 轴位移动画
 * - 解锁成功弹跳动画
 * - 小球弹入/弹出动画
 * - 触觉反馈
 */
class BallAnimator(
    private val windowManager: WindowManager,
    private val getBallView: () -> View?,
    private val getBallParams: () -> WindowManager.LayoutParams?
) {
    private var snapAnimator: ValueAnimator? = null

    fun animateBallX(
        targetX: Int,
        duration: Long,
        interpolator: Interpolator = DecelerateInterpolator(),
        onEnd: (() -> Unit)? = null
    ) {
        val params = getBallParams() ?: return
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                params.x = it.animatedValue as Int
                getBallView()?.let { v ->
                    try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                }
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) { if (!cancelled) onEnd() }
                })
            }
            start()
        }
    }

    fun cancelSnap() { snapAnimator?.cancel() }

    fun animateUnlockSuccess(icon: ImageView, onComplete: () -> Unit) {
        icon.animate()
            .scaleX(1.55f).scaleY(1.55f).setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                icon.animate()
                    .scaleX(0.85f).scaleY(0.85f).setDuration(100)
                    .withEndAction {
                        icon.animate()
                            .scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(OvershootInterpolator(2.5f))
                            .withEndAction(onComplete)
                            .start()
                    }.start()
            }.start()
    }

    fun animateMiniBallIn(view: View, delayMs: Long) {
        view.postDelayed({
            view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(150).setInterpolator(OvershootInterpolator(2f)).start()
        }, delayMs)
    }

    fun animateMiniBallOut(view: View, onEnd: () -> Unit) {
        view.animate().scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(100).withEndAction(onEnd).start()
    }

    fun vibrateSuccess(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                else
                    @Suppress("DEPRECATION") v.vibrate(80)
            }
        } catch (_: Exception) {}
    }
}