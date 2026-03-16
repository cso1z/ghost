package com.xyz.ghost

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.os.SystemClock

class OverlayService : Service() {

    companion object {
        const val ACTION_STOPPED = "com.xyz.ghost.ACTION_STOPPED"
        const val ACTION_STOP = "com.xyz.ghost.ACTION_STOP"
        private const val CHANNEL_ID = "ghost_channel"
        private const val NOTIFICATION_ID = 1
        private const val UNLOCK_HOLD_MS = 1500L
        private const val SNAP_DURATION = 280L
        private const val REVEAL_DURATION = 180L
        private const val AUTO_HIDE_DELAY = 1500L

        @Volatile
        var isRunning = false
            private set
    }

    enum class State { IDLE, ACTIVE }

    private var state = State.IDLE
    private lateinit var windowManager: WindowManager

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var overlayView: View? = null

    private var snappedToLeft = false
    private var isHidden = false
    // Bug3 fix: 成功动画进行中，不允许外部修改 alpha
    private var isSuccessAnimating = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { slideToHidden() }
    private var snapAnimator: ValueAnimator? = null

    private val overlayType
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // Bug1 fix: 球体视觉尺寸 56dp，Window 尺寸 96dp（含 20dp 四周溢出空间）
    private val ballSizePx get() = dpToPx(56)
    private val overflowPx get() = dpToPx(20)
    private val windowSizePx get() = ballSizePx + 2 * overflowPx // 96dp

    // 球体视觉左边缘 = params.x + overflowPx
    // 球体视觉中心  = params.x + overflowPx + ballSizePx / 2

    // 50% 隐藏：球体中心对齐屏幕边缘
    private fun hiddenX(left: Boolean) =
        if (left) -(overflowPx + ballSizePx / 2)
        else screenWidth() - overflowPx - ballSizePx / 2

    // 完整展示：球体视觉边缘距屏幕边缘 12dp
    private fun revealedX(left: Boolean) =
        if (left) dpToPx(12) - overflowPx
        else screenWidth() - dpToPx(12) - overflowPx - ballSizePx

    // ── 生命周期 ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        removeBall()
        removeOverlay()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 悬浮球 ───────────────────────────────────────────────────────

    private fun showBall() {
        val view = LayoutInflater.from(this).inflate(R.layout.ball_layout, null)
        val icon = view.findViewById<ImageView>(R.id.ball_icon)
        val progressRing = view.findViewById<View>(R.id.ball_progress)

        snappedToLeft = false
        isHidden = false
        isSuccessAnimating = false

        // Bug1 fix: Window 使用 windowSizePx（96dp）
        val params = WindowManager.LayoutParams(
            windowSizePx, windowSizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = revealedX(snappedToLeft)
            y = dpToPx(120)
        }

        setupBallTouch(icon, progressRing, params)
        windowManager.addView(view, params)
        ballView = view
        ballParams = params
        updateBallIcon(icon)

        scheduleHide()
    }

    private fun setupBallTouch(
        icon: ImageView,
        progressRing: View,
        params: WindowManager.LayoutParams
    ) {
        var wasHiddenOnDown = false
        var isDragging = false
        var touchStartX = 0f
        var touchStartY = 0f
        var paramStartX = 0
        var paramStartY = 0
        // 用时间差判断是否满足解锁时长，彻底消除 Handler 竞争
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

                    snapAnimator?.cancel()
                    paramStartX = params.x
                    paramStartY = params.y

                    cancelHide()
                    if (isHidden) revealBall() else animateBallX(revealedX(snappedToLeft), REVEAL_DURATION)

                    if (state == State.ACTIVE) {
                        holdStartMs = SystemClock.elapsedRealtime()
                        progressRing.alpha = 1f
                        progressRing.scaleX = 0f
                        progressRing.scaleY = 0f
                        progressRing.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(UNLOCK_HOLD_MS)
                            .start()
                    }
                    if (!isSuccessAnimating) icon.alpha = 1f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && !isSuccessAnimating && (abs(dx) > 8f || abs(dy) > 8f)) {
                        isDragging = true
                        holdStartMs = 0L   // 拖动则放弃解锁
                        progressRing.animate().cancel()
                        progressRing.alpha = 0f
                        snapAnimator?.cancel()
                        paramStartX = params.x
                    }
                    if (isDragging) {
                        params.x = max(
                            hiddenX(true),
                            min(hiddenX(false), paramStartX + dx.toInt())
                        )
                        params.y = max(0, min(screenHeight() - windowSizePx, paramStartY + dy.toInt()))
                        updateBallLayout(params)
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
                        // 用时间差判断解锁，无竞争
                        val held = if (holdStartMs > 0) SystemClock.elapsedRealtime() - holdStartMs else 0L
                        when {
                            held >= UNLOCK_HOLD_MS -> playUnlockSuccessAnimation()
                            wasHiddenOnDown -> scheduleHide()
                            state == State.IDLE && !isSuccessAnimating -> {
                                activateProtection(icon)
                                scheduleHide()
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

    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        // 球体视觉中心判断吸附方向
        snappedToLeft = (params.x + overflowPx + ballSizePx / 2) < screenWidth() / 2
        isHidden = false
        animateBallX(revealedX(snappedToLeft), SNAP_DURATION, DecelerateInterpolator())
        scheduleHide()
    }

    private fun revealBall(onEnd: (() -> Unit)? = null) {
        isHidden = false
        animateBallX(revealedX(snappedToLeft), REVEAL_DURATION, onEnd = onEnd)
    }

    private fun slideToHidden() {
        isHidden = true
        animateBallX(hiddenX(snappedToLeft), SNAP_DURATION, DecelerateInterpolator())
    }

    private fun scheduleHide(delayMs: Long = AUTO_HIDE_DELAY) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delayMs)
    }

    private fun cancelHide() {
        handler.removeCallbacks(hideRunnable)
    }

    private fun animateBallX(
        targetX: Int,
        duration: Long = SNAP_DURATION,
        interpolator: android.view.animation.Interpolator = DecelerateInterpolator(),
        onEnd: (() -> Unit)? = null
    ) {
        val params = ballParams ?: return
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                params.x = it.animatedValue as Int
                updateBallLayout(params)
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    // Bug4 fix: 取消时不触发 onEnd 回调
                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd()
                    }
                })
            }
            start()
        }
    }

    private fun updateBallLayout(params: WindowManager.LayoutParams) {
        ballView?.let { try { windowManager.updateViewLayout(it, params) } catch (_: Exception) {} }
    }

    private fun removeBall() {
        snapAnimator?.cancel()
        ballView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        ballView = null
        ballParams = null
    }

    // ── 保护层 ───────────────────────────────────────────────────────

    private fun activateProtection(icon: ImageView) {
        cancelHide()
        state = State.ACTIVE
        updateBallIcon(icon)

        val ball = ballView ?: return
        val params = ballParams ?: return

        try { windowManager.removeView(ball) } catch (_: Exception) {}

        val overlay = View(this).apply { isClickable = true }
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(overlay, overlayParams)
        overlayView = overlay
        try { windowManager.addView(ball, params) } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    // ── 解锁动画 ─────────────────────────────────────────────────────

    private fun playUnlockSuccessAnimation() {
        val icon = ballView?.findViewById<ImageView>(R.id.ball_icon)
            ?: run { deactivateProtection(); return }
        val progressRing = ballView?.findViewById<View>(R.id.ball_progress)

        isSuccessAnimating = true
        progressRing?.animate()?.cancel()
        progressRing?.alpha = 0f

        // ★ 立即移除遮罩 — 解锁即时生效，动画只是庆祝反馈
        state = State.IDLE
        removeOverlay()
        vibrateSuccess()

        icon.alpha = 1f
        icon.setBackgroundResource(R.drawable.bg_ball_success)

        // 弹跳庆祝动画（不阻塞解锁）
        icon.animate()
            .scaleX(1.55f).scaleY(1.55f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                icon.animate()
                    .scaleX(0.85f).scaleY(0.85f)
                    .setDuration(100)
                    .withEndAction {
                        icon.animate()
                            .scaleX(1.0f).scaleY(1.0f)
                            .setDuration(150)
                            .setInterpolator(OvershootInterpolator(2.5f))
                            .withEndAction { finishSuccessAnimation() }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    // 动画结束后的收尾（恢复图标、安排收起）
    private fun finishSuccessAnimation() {
        isSuccessAnimating = false
        ballView?.findViewById<ImageView>(R.id.ball_icon)?.let { icon ->
            icon.setBackgroundResource(R.drawable.bg_ball)
            updateBallIcon(icon)
            icon.alpha = 0.9f
        }
        scheduleHide()
    }

    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") v.vibrate(80)
                }
            }
        } catch (_: Exception) {}
    }

    // 仅在异常路径（无法获取 icon）时直接调用
    private fun deactivateProtection() {
        isSuccessAnimating = false
        state = State.IDLE
        removeOverlay()
        ballView?.findViewById<ImageView>(R.id.ball_icon)?.let { icon ->
            icon.setBackgroundResource(R.drawable.bg_ball)
            updateBallIcon(icon)
            icon.alpha = 0.9f
        }
        scheduleHide()
    }

    // ── 工具 ─────────────────────────────────────────────────────────

    private fun updateBallIcon(icon: ImageView) {
        icon.setImageResource(
            if (state == State.ACTIVE) R.drawable.ic_lock else R.drawable.ic_shield
        )
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun screenWidth() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds.width()
        else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(dm)
            dm.widthPixels
        }

    private fun screenHeight() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds.height()
        else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(dm)
            dm.heightPixels
        }

    // ── 通知 ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ghost 防误触", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "防误触保护运行中"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Ghost 运行中")
            .setContentText("点击悬浮球开启保护 · 长按 1.5 秒关闭")
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}