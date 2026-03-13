package com.xyz.ghost

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
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val ACTION_STOPPED = "com.xyz.ghost.ACTION_STOPPED"
        const val ACTION_STOP = "com.xyz.ghost.ACTION_STOP"
        private const val CHANNEL_ID = "ghost_channel"
        private const val NOTIFICATION_ID = 1
        private const val UNLOCK_HOLD_MS = 1500L

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

    private val handler = Handler(Looper.getMainLooper())
    private val unlockRunnable = Runnable { playUnlockSuccessAnimation() }

    private val overlayType
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

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
        removeBall()
        removeOverlay()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 悬浮球 ─────────────────────────────────────────────────────────

    private fun showBall() {
        val view = LayoutInflater.from(this).inflate(R.layout.ball_layout, null)
        val ballIcon = view.findViewById<ImageView>(R.id.ball_icon)
        val progressRing = view.findViewById<View>(R.id.ball_progress)

        val ballSize = dpToPx(56)
        val params = WindowManager.LayoutParams(
            ballSize, ballSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - ballSize - dpToPx(16)
            y = dpToPx(120)
        }

        setupBallTouch(ballIcon, progressRing, params)
        windowManager.addView(view, params)
        ballView = view
        ballParams = params
        updateBallIcon(ballIcon)
    }

    private fun setupBallTouch(
        icon: ImageView,
        progressRing: View,
        params: WindowManager.LayoutParams
    ) {
        var isDragging = false
        var touchStartX = 0f
        var touchStartY = 0f
        var paramStartX = 0
        var paramStartY = 0

        icon.setOnTouchListener { _, event ->
            val ballSize = dpToPx(56)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    paramStartX = params.x
                    paramStartY = params.y

                    if (state == State.ACTIVE) {
                        // 长按解锁倒计时
                        progressRing.alpha = 1f
                        progressRing.scaleX = 0f
                        progressRing.scaleY = 0f
                        progressRing.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(UNLOCK_HOLD_MS)
                            .start()
                        handler.postDelayed(unlockRunnable, UNLOCK_HOLD_MS)
                    }
                    icon.alpha = 1f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && (abs(dx) > 8f || abs(dy) > 8f)) {
                        isDragging = true
                        handler.removeCallbacks(unlockRunnable)
                        progressRing.animate().cancel()
                        progressRing.alpha = 0f
                    }
                    if (isDragging) {
                        params.x = max(0, min(screenWidth() - ballSize, paramStartX + dx.toInt()))
                        params.y = max(0, min(screenHeight() - ballSize, paramStartY + dy.toInt()))
                        windowManager.updateViewLayout(icon.rootView, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(unlockRunnable)
                    progressRing.animate().cancel()
                    progressRing.alpha = 0f
                    icon.alpha = 0.85f

                    if (!isDragging && state == State.IDLE) {
                        activateProtection(icon)
                    }
                    true
                }

                else -> false
            }
        }
        icon.alpha = 0.85f
    }

    private fun removeBall() {
        ballView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        ballView = null
    }

    // ── 保护层 ─────────────────────────────────────────────────────────

    private fun activateProtection(icon: ImageView) {
        state = State.ACTIVE
        updateBallIcon(icon)

        // 先移除悬浮球，再加入保护层，最后重新加入悬浮球（保证球始终在最顶层）
        val savedBall = ballView
        val savedParams = ballParams
        if (savedBall != null && savedParams != null) {
            try { windowManager.removeView(savedBall) } catch (_: Exception) {}
        }

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

        if (savedBall != null && savedParams != null) {
            try { windowManager.addView(savedBall, savedParams) } catch (_: Exception) {}
        }
    }

    private fun playUnlockSuccessAnimation() {
        val icon = ballView?.findViewById<ImageView>(R.id.ball_icon) ?: run {
            deactivateProtection()
            return
        }
        // 震动反馈
        vibrateSuccess()
        // 切换为成功背景色
        icon.setBackgroundResource(R.drawable.bg_ball_success)
        icon.setImageResource(R.drawable.ic_lock)
        // 弹跳动画：放大 → 缩小 → 正常，完成后解锁
        icon.animate()
            .scaleX(1.55f).scaleY(1.55f)
            .setDuration(160)
            .withEndAction {
                icon.animate()
                    .scaleX(0.85f).scaleY(0.85f)
                    .setDuration(120)
                    .withEndAction {
                        icon.animate()
                            .scaleX(1.0f).scaleY(1.0f)
                            .setDuration(100)
                            .withEndAction { deactivateProtection() }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun vibrateSuccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(80)
            }
        }
    }

    private fun deactivateProtection() {
        state = State.IDLE
        removeOverlay()
        ballView?.findViewById<ImageView>(R.id.ball_icon)?.let { icon ->
            icon.setBackgroundResource(R.drawable.bg_ball)
            updateBallIcon(icon)
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    // ── 工具方法 ────────────────────────────────────────────────────────

    private fun updateBallIcon(icon: ImageView) {
        if (state == State.ACTIVE) {
            icon.setImageResource(R.drawable.ic_lock)
            icon.alpha = 1f
        } else {
            icon.setImageResource(R.drawable.ic_shield)
            icon.alpha = 0.9f
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun screenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(dm)
            dm.widthPixels
        }
    }

    private fun screenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(dm)
            dm.heightPixels
        }
    }

    // ── 通知 ────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ghost 防误触", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "防误触保护运行中"
                setShowBadge(false)
            }
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
            .setContentText("点击悬浮球开启保护，长按 1.5 秒关闭保护")
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}